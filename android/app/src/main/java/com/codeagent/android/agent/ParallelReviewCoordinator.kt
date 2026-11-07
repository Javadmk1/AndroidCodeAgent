package com.codeagent.android.agent

import com.codeagent.android.llm.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ParallelReviewResult(val role: String, val report: String, val error: String? = null)

class ParallelReviewCoordinator(private val cacheDirectory: File) {
    suspend fun review(
        source: File,
        config: ProviderConfig,
        apiKey: String,
        onActivity: suspend (String) -> Unit = {}
    ): List<ParallelReviewResult> = withContext(Dispatchers.IO) {
        val root = File(cacheDirectory, "parallel-review-" + UUID.randomUUID())
        require(root.mkdirs()) { "ساخت فضای بررسی موازی ممکن نشد." }
        val roles = listOf(
            "امنیت" to "بررسی امنیتی: مسیرهای فایل، نشت اسرار، ورودی‌های غیرقابل اعتماد، مجوزها و مرزهای اعتماد را بررسی کن. فقط یافته‌های قابل اثبات با مسیر فایل و شدت گزارش بده.",
            "باگ و منطق" to "بررسی باگ: جریان‌های اصلی، خطاها، شرایط مرزی، لغو عملیات، همزمانی و مدیریت وضعیت را بررسی کن. فقط یافته‌های قابل بازتولید یا مستند با مسیر فایل و اثر گزارش بده.",
            "معماری و تست" to "بررسی معماری و تست: طراحی ماژول‌ها، نگهداشت‌پذیری، پوشش تست، ریسک رگرسیون و نقاط فاقد تست را بررسی کن. پیشنهادهای اولویت‌بندی‌شده و مسیر فایل بده."
        )
        try {
            val snapshots = roles.mapIndexed { index, _ ->
                val target = File(root, "review-" + index)
                copyBounded(source.canonicalFile, target)
                target
            }
            supervisorScope {
                roles.mapIndexed { index, pair ->
                    async(Dispatchers.IO) {
                        val role = pair.first
                        val instruction = pair.second
                        runCatching {
                            onActivity("ایجنت " + role + ": بررسی مستقل شروع شد.")
                            val runtime = AgentRuntime(ToolRegistry(WorkspaceTools(snapshots[index])))
                            val result = runtime.run(
                                config = config,
                                apiKey = apiKey,
                                conversation = listOf(
                                    "system" to "تو یک بازبین کد فقط‌خواندنی هستی. هیچ فایلی را تغییر نده. ابزارهای نوشتن و ساخت تأیید نمی‌شوند. از شواهد داخل مخزن استفاده کن؛ یافته ساختگی نساز. گزارش را کوتاه، با شدت، مسیر فایل و دلیل ارائه کن.",
                                    "user" to "این پروژه را فقط از زاویه تخصصی زیر بررسی کن.\n" + instruction + "\n\nساختار و فایل‌های پروژه را با ابزارها بخوان و گزارش نهایی بده."
                                ),
                                approveWrite = { _, _ -> false },
                                approveBuild = { false },
                                onActivity = { event -> onActivity("ایجنت " + role + ": " + event) }
                            )
                            ParallelReviewResult(role, result.answer.ifBlank { "گزارشی تولید نشد." })
                        }.getOrElse { error ->
                            ParallelReviewResult(role, "", error.message ?: "خطای نامشخص")
                        }
                    }
                }.map { it.await() }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun copyBounded(source: File, destination: File) {
        require(source.isDirectory) { "پروژه فعال پوشه نیست." }
        val excluded = setOf(".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", ".codeagent-history")
        var files = 0
        var bytes = 0L
        source.walkTopDown().onEnter { dir ->
            dir == source || (dir.name !in excluded && !java.nio.file.Files.isSymbolicLink(dir.toPath()))
        }.forEach { file ->
            require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "بررسی موازی به لینک نمادین برخورد کرد؛ برای امنیت متوقف شد." }
            val relative = source.toPath().relativize(file.toPath()).toString()
            if (relative.isBlank()) return@forEach
            if (ProjectExportRules.isSensitivePath(relative.replace(File.separatorChar, '/'))) return@forEach
            val target = File(destination, relative)
            if (file.isDirectory) {
                require(target.exists() || target.mkdirs()) { "ساخت پوشه snapshot ناموفق بود." }
            } else if (file.isFile) {
                files++
                bytes += file.length()
                require(files <= MAX_FILES && bytes <= MAX_BYTES && file.length() <= MAX_FILE_BYTES) {
                    "پروژه برای بررسی موازی بیش از حد بزرگ است؛ محدودیت: ۸۰۰ فایل و ۲۴ مگابایت."
                }
                target.parentFile?.let { require(it.exists() || it.mkdirs()) }
                file.copyTo(target, overwrite = true)
            }
        }
    }

    private companion object {
        const val MAX_FILES = 800
        const val MAX_BYTES = 24L * 1024 * 1024
        const val MAX_FILE_BYTES = 1L * 1024 * 1024
    }
}
