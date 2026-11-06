package com.codeagent.android.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** SAF import/export for a bounded app-private project workspace. */
class ProjectTransfer(
    private val context: Context,
    private val workspace: File
) {
    data class TransferResult(val files: Int, val bytes: Long)

    /**
     * Imports into a staging directory first. The current workspace is replaced only
     * after every source file has been copied successfully, preventing mixed projects
     * and preserving the previous project when an import fails.
     */
    suspend fun importTree(treeUri: Uri): TransferResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("پوشهٔ انتخاب‌شده قابل خواندن نیست.")
        require(root.isDirectory) { "یک پوشه انتخاب کن." }

        val parent = workspace.absoluteFile.parentFile ?: error("مسیر فضای پروژه نامعتبر است.")
        if (!parent.exists() && !parent.mkdirs()) error("ساخت پوشهٔ موقت ناموفق بود.")
        val staging = File(parent, "${workspace.name}.importing")
        val backup = File(parent, "${workspace.name}.previous")
        staging.deleteRecursively()
        backup.deleteRecursively()
        if (!staging.mkdirs()) error("ساخت فضای موقت پروژه ناموفق بود.")

        var count = 0
        var total = 0L
        try {
            fun copyDirectory(source: DocumentFile, target: File) {
                source.listFiles().forEach { child ->
                    val name = child.name ?: return@forEach
                    if (name in SKIP_DIRS) return@forEach
                    val safeName = name.replace("/", "_").replace("\\", "_")
                    val destination = File(target, safeName).canonicalFile
                    require(destination.toPath().startsWith(staging.canonicalFile.toPath())) {
                        "مسیر فایل نامعتبر است."
                    }
                    if (child.isDirectory) {
                        require(count < MAX_FILES) { "تعداد فایل‌ها بیش از حد مجاز است." }
                        if (!destination.exists() && !destination.mkdirs()) error("ساخت پوشه ناموفق بود.")
                        copyDirectory(child, destination)
                    } else if (child.isFile) {
                        val size = child.length()
                        require(size in 0..MAX_FILE_BYTES) { "فایل $name بیش از ۱۰ مگابایت است." }
                        require(total + size <= MAX_TOTAL_BYTES) { "حجم پروژه بیش از ۱۰۰ مگابایت است." }
                        destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("ساخت پوشه ناموفق بود.") }
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
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

            copyDirectory(root, staging)
            if (workspace.exists() && !workspace.renameTo(backup)) {
                error("انتقال پروژهٔ قبلی به نسخهٔ پشتیبان ناموفق بود.")
            }
            if (!staging.renameTo(workspace)) {
                if (backup.exists()) backup.renameTo(workspace)
                error("جایگزینی پروژه ناموفق بود؛ نسخهٔ قبلی بازیابی شد.")
            }
            backup.deleteRecursively()
            TransferResult(count, total)
        } catch (error: Exception) {
            staging.deleteRecursively()
            if (!workspace.exists() && backup.exists()) backup.renameTo(workspace)
            throw error
        }
    }

    /** Import a ZIP archive without allowing path traversal or partial workspace replacement. */
    suspend fun importZip(source: Uri): TransferResult = withContext(Dispatchers.IO) {
        val parent = workspace.absoluteFile.parentFile ?: error("مسیر فضای پروژه نامعتبر است.")
        if (!parent.exists() && !parent.mkdirs()) error("ساخت پوشهٔ موقت ناموفق بود.")
        val staging = File(parent, "${workspace.name}.zip-importing")
        val backup = File(parent, "${workspace.name}.zip-previous")
        staging.deleteRecursively()
        backup.deleteRecursively()
        check(staging.mkdirs()) { "ساخت فضای موقت پروژه ناموفق بود." }
        var count = 0
        var entryCount = 0
        var total = 0L
        val seen = HashSet<String>()
        try {
            val input = context.contentResolver.openInputStream(source) ?: error("خواندن فایل ZIP ناموفق بود.")
            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_FILES) { "تعداد ورودی‌های ZIP بیش از حد مجاز است." }
                    val rawName = entry.name.replace('\\', '/')
                    require(rawName.isNotBlank() && !rawName.startsWith("/") &&
                        !rawName.matches(Regex("^[A-Za-z]:.*"))) { "مسیر نامعتبر در ZIP: $rawName" }
                    val normalizedRaw = rawName.trimEnd('/')
                    require(normalizedRaw.isNotBlank()) { "نام فایل ZIP نامعتبر است." }
                    val segments = normalizedRaw.split('/')
                    require(segments.none { it.isBlank() || it == "." || it == ".." }) {
                        "مسیر ناامن در ZIP: $rawName"
                    }
                    require(segments.none { it in SKIP_DIRS }) { "آرشیو شامل پوشهٔ خروجی غیرمجاز است: $rawName" }
                    val normalized = segments.joinToString("/")
                    require(seen.add(normalized)) { "نام تکراری در ZIP: $normalized" }
                    val target = File(staging, normalized).canonicalFile
                    require(target.toPath().startsWith(staging.canonicalFile.toPath())) { "مسیر فایل از محدوده پروژه خارج می‌شود." }
                    if (entry.isDirectory) {
                        check(target.exists() || target.mkdirs()) { "ساخت پوشه ناموفق بود: $normalized" }
                    } else {
                        count++
                        require(count <= MAX_FILES) { "تعداد فایل‌های ZIP بیش از حد مجاز است." }
                        check(target.parentFile?.exists() == true || target.parentFile?.mkdirs() == true) {
                            "ساخت پوشه ناموفق بود: $normalized"
                        }
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(8192)
                            var fileBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                fileBytes += read
                                total += read
                                require(fileBytes <= MAX_FILE_BYTES) { "فایل $normalized بیش از ۱۰ مگابایت است." }
                                require(total <= MAX_TOTAL_BYTES) { "حجم استخراج‌شده بیش از ۱۰۰ مگابایت است." }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(count > 0) { "فایل ZIP هیچ فایل پروژه‌ای ندارد." }
            if (workspace.exists()) check(workspace.renameTo(backup)) { "پشتیبان‌گیری از پروژه قبلی ناموفق بود." }
            if (!staging.renameTo(workspace)) {
                if (backup.exists()) backup.renameTo(workspace)
                error("جایگزینی پروژه ناموفق بود؛ نسخه قبلی بازیابی شد.")
            }
            backup.deleteRecursively()
            TransferResult(count, total)
        } catch (error: Exception) {
            staging.deleteRecursively()
            if (!workspace.exists() && backup.exists()) backup.renameTo(workspace)
            throw error
        }
    }

    suspend fun exportZip(destination: Uri): TransferResult = withContext(Dispatchers.IO) {
        require(workspace.isDirectory) { "فضای پروژه وجود ندارد." }
        var count = 0
        var total = 0L
        context.contentResolver.openOutputStream(destination, "w")?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                workspace.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = workspace.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    if (relative.split('/').any { it in SKIP_DIRS }) return@forEach
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
        val SKIP_DIRS = setOf(".git", ".gradle", "build", ".idea", ".kotlin", ".codeagent-history")
    }
}
