package com.codeagent.android.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class LlmClient {
    fun stream(
        config: ProviderConfig,
        apiKey: String,
        messages: List<ChatMessage>
    ): Flow<String> = callbackFlow {
        val worker = launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val anthropic = config.provider.equals("anthropic", ignoreCase = true)
                val endpoint = ApiEndpoint.chat(config)
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                    if (anthropic) {
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    } else {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                }

                val body = if (anthropic) {
                    LlmRequestBodyBuilder.anthropicBody(config, messages)
                } else {
                    LlmRequestBodyBuilder.openAiBody(config, messages)
                }
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val errorText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val detail = runCatching {
                        val json = JSONObject(errorText)
                        json.optJSONObject("error")?.optString("message")
                            ?: json.optString("message").takeIf { it.isNotBlank() }
                            ?: errorText
                    }.getOrDefault(errorText)
                    val hint = when (code) {
                        401, 403 -> "کلید API یا مجوز دسترسی را بررسی کن."
                        404 -> "آدرس endpoint یا نام مدل اشتباه است."
                        413 -> "حجم درخواست یا تصویر از حد مجاز سرویس بیشتر است."
                        429 -> "سهمیه یا محدودیت درخواست سرویس پر شده است."
                        else -> "پاسخ ناموفق از سرویس دریافت شد."
                    }
                    throw IllegalStateException("HTTP $code · $hint ${detail.take(800)}")
                }

                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                        val text = if (anthropic) {
                            json.optJSONObject("delta")?.optString("text").orEmpty()
                        } else {
                            json.optJSONArray("choices")?.optJSONObject(0)
                                ?.optJSONObject("delta")?.optString("content").orEmpty()
                        }
                        if (text.isNotEmpty()) trySend(text)
                    }
                }
                close()
            } catch (error: Exception) {
                close(error)
            } finally {
                connection?.disconnect()
            }
        }
        awaitClose { worker.cancel() }
    }
}
