package com.codeagent.android.agent

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** SAF import/export for a bounded app-private project workspace. */
class ProjectTransfer(
    private val resolver: ContentResolver,
    private val workspace: File
) {
    data class TransferResult(val files: Int, val bytes: Long)

    suspend fun importTree(treeUri: Uri): TransferResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(resolver.contextOrNull(), treeUri)
            ?: throw IllegalArgumentException("پوشهٔ انتخاب‌شده قابل خواندن نیست.")
        require(root.isDirectory) { "یک پوشه انتخاب کن." }
        var count = 0
        var total = 0L
        fun copyDirectory(source: DocumentFile, target: File) {
            source.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                if (name.startsWith(".") || name in SKIP_DIRS) return@forEach
                val safeName = name.replace("/", "_").replace("\\", "_")
                val destination = File(target, safeName).canonicalFile
                require(destination.toPath().startsWith(workspace.canonicalFile.toPath())) { "مسیر فایل نامعتبر است." }
                if (child.isDirectory) {
                    require(count < MAX_FILES) { "تعداد فایل‌ها بیش از حد مجاز است." }
                    if (!destination.exists()) destination.mkdirs()
                    copyDirectory(child, destination)
                } else if (child.isFile) {
                    val size = child.length()
                    require(size in 0..MAX_FILE_BYTES) { "فایل $name بیش از ۱۰ مگابایت است." }
                    require(total + size <= MAX_TOTAL_BYTES) { "حجم پروژه بیش از ۱۰۰ مگابایت است." }
                    destination.parentFile?.mkdirs()
                    resolver.openInputStream(child.uri)?.use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= MAX_TOTAL_BYTES) { "حجم پروژه بیش از ۱۰۰ مگابایت است." }
                                output.write(buffer, 0, read)
                            }
                        }
                    } ?: error("خواندن فایل $name ناموفق بود.")
                    count++
                    require(count <= MAX_FILES) { "تعداد فایل‌ها بیش از حد مجاز است." }
                }
            }
        }
        if (!workspace.exists()) workspace.mkdirs()
        copyDirectory(root, workspace)
        TransferResult(count, total)
    }

    suspend fun exportZip(destination: Uri): TransferResult = withContext(Dispatchers.IO) {
        require(workspace.isDirectory) { "فضای پروژه وجود ندارد." }
        var count = 0
        var total = 0L
        resolver.openOutputStream(destination, "w")?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                workspace.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = workspace.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    if (relative.split('/').any { it.startsWith(".") || it in SKIP_DIRS }) return@forEach
                    require(file.length() <= MAX_FILE_BYTES) { "فایل $relative بیش از ۱۰ مگابایت است." }
                    total += file.length()
                    require(total <= MAX_TOTAL_BYTES) { "حجم خروجی بیش از ۱۰۰ مگابایت است." }
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                    require(count <= MAX_FILES) { "تعداد فایل‌ها بیش از حد مجاز است." }
                }
            }
        } ?: error("ذخیرهٔ فایل خروجی ناموفق بود.")
        TransferResult(count, total)
    }

    private companion object {
        const val MAX_FILES = 5_000
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
        val SKIP_DIRS = setOf(".git", ".gradle", "build", ".idea", ".kotlin")
    }
}
