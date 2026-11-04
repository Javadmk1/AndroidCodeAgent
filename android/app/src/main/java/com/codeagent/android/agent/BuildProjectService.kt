package com.codeagent.android.agent

import android.content.Context
import android.net.Uri
import com.codeagent.android.SecureKeyStore
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

class BuildProjectService(
    private val context: Context,
    private val keys: SecureKeyStore
) {
    suspend fun build(project: File, task: String, onActivity: suspend (String) -> Unit): JSONObject {
        val prefs = context.getSharedPreferences("cloud_builder_config", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", "").orEmpty()
        val token = keys.read("cloud_builder").orEmpty()
        require(endpoint.isNotBlank()) { "آدرس سرویس ساخت تنظیم نشده است. ابتدا اتصال Cloud Builder را در تنظیمات ساخت ذخیره کن." }
        require(token.isNotBlank()) { "توکن سرویس ساخت ذخیره نشده است." }
        val client = CloudBuildClient(context)
        client.validateEndpoint(endpoint)
        client.validateTask(task)
        require(project.isDirectory) { "پوشه پروژه وجود ندارد." }

        val archive = File(context.cacheDir, "agent-project-${System.currentTimeMillis()}.zip")
        try {
            onActivity("آماده‌سازی فایل‌های پروژه برای ساخت امن...")
            createArchive(project, archive)
            onActivity("آپلود پروژه و ارسال دستور Gradle: $task")
            val buildId = client.submitZip(endpoint, token, Uri.fromFile(archive), ".", task)
            var previousLog = ""
            repeat(700) {
                val state = client.getStatus(endpoint, token, buildId)
                val log = state.log
                if (log.length > previousLog.length) {
                    onActivity(log.substring(previousLog.length).trim().takeLast(2500))
                    previousLog = log
                }
                onActivity("وضعیت ساخت: ${state.status} · دستور: $task")
                if (state.status == "failed") error("Gradle $task ناموفق بود: ${state.error ?: "خطای نامشخص"}")
                if (state.status == "completed") {
                    if (task == "assembleDebug") {
                        require(state.artifactReady) { "ساخت تمام شد اما APK خروجی آماده نیست." }
                        val downloaded = client.downloadArtifact(endpoint, token, buildId)
                        val finalArtifact = File(context.cacheDir, "last-cloud-build.zip")
                        downloaded.copyTo(finalArtifact, overwrite = true)
                        val entries = ZipFile(finalArtifact).use { zip ->
                            val array = org.json.JSONArray()
                            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", true) }
                                .forEach { array.put(JSONObject().put("path", it.name).put("size", it.size)) }
                            array
                        }
                        require(entries.length() > 0) { "فایل ZIP دریافت شد اما APK داخل آن وجود ندارد." }
                        onActivity("APK ساخته و دریافت شد: ${entries.length()} فایل")
                        return JSONObject().put("ok", true).put("task", task)
                            .put("artifact", finalArtifact.name).put("artifact_size", finalArtifact.length())
                            .put("apks", entries).put("build_id", buildId)
                    }
                    return JSONObject().put("ok", true).put("task", task).put("build_id", buildId)
                        .put("message", "دستور Gradle با موفقیت اجرا شد؛ این دستور APK تولید نمی‌کند.")
                }
                delay(3000)
            }
            error("مهلت انتظار ساخت تمام شد. شناسه ساخت: $buildId")
        } finally {
            archive.delete()
        }
    }

    private fun createArchive(root: File, output: File) {
        var count = 0
        var total = 0L
        val excluded = setOf(".git", ".gradle", ".idea", ".kotlin", "build", "local.properties")
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            root.walkTopDown().forEach { file ->
                val relative = root.toPath().relativize(file.toPath()).toString()
                if (relative.isBlank()) return@forEach
                if (relative.split(File.separatorChar).any { it in excluded }) return@forEach
                if (file.isDirectory) return@forEach
                require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "فایل نامعتبر یا پیوند نمادین در پروژه: $relative" }
                val lowerName = file.name.lowercase()
                require(lowerName != "key.properties" && lowerName != "google-services.json" &&
                    !lowerName.endsWith(".jks") && !lowerName.endsWith(".keystore") &&
                    !lowerName.endsWith(".p12") && !lowerName.endsWith(".pfx") &&
                    !lowerName.endsWith(".pem") && lowerName != ".env" &&
                    !lowerName.contains("service-account") && !lowerName.contains("credentials")) {
                    "فایل دارای نام حساس شناسایی شد؛ قبل از آپلود آن را از پروژه خارج کن: $relative"
                }
                val size = file.length()
                require(size <= 10L * 1024 * 1024) { "فایل $relative بیش از ۱۰ مگابایت است." }
                total += size
                require(total <= 100L * 1024 * 1024) { "حجم پروژه بیش از ۱۰۰ مگابایت است." }
                count++
                require(count <= 5000) { "تعداد فایل‌های پروژه بیش از حد مجاز است." }
                zip.putNextEntry(ZipEntry(relative.replace(File.separatorChar, '/')))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
