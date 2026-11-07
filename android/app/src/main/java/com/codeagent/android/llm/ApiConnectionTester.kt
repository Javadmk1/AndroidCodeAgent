package com.codeagent.android.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Sends a tiny non-streaming request to verify endpoint, key and model before chat. */
class ApiConnectionTester {
    suspend fun test(config: ProviderConfig, apiKey: String): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "کلید API خالی است." }
        require(config.model.isNotBlank()) { "نام مدل خالی است." }
        val anthropic = config.provider.equals("anthropic", ignoreCase = true)
        val endpoint = ApiEndpoint.chat(config)
        val body = if (anthropic) {
            JSONObject().put("model", config.model).put("max_tokens", 12).put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK")))
        } else {
            JSONObject().put("model", config.model).put("stream", false).put("max_tokens", 12)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK")))
        }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (anthropic) {
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
            } else connection.setRequestProperty("Authorization", "Bearer " + apiKey)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching {
                    val json = JSONObject(raw)
                    json.optJSONObject("error")?.optString("message")
                        ?: json.optString("message").takeIf { it.isNotBlank() } ?: raw
                }.getOrDefault(raw)
                val hint = when (code) {
                    401, 403 -> "کلید API یا مجوز دسترسی را بررسی کن."
                    404 -> "آدرس endpoint یا نام مدل اشتباه است."
                    429 -> "سهمیه یا محدودیت درخواست سرویس پر شده است."
                    else -> "پاسخ ناموفق از سرویس دریافت شد."
                }
                error("HTTP " + code + " · " + hint + " " + detail.take(500))
            }
            runCatching { JSONObject(raw) }.getOrElse { error("اتصال برقرار شد اما پاسخ JSON معتبر نبود.") }
            "اتصال API موفق است · سرویس پاسخ داد"
        } finally { connection.disconnect() }
    }
}
