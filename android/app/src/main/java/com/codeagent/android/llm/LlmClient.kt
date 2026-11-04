package com.codeagent.android.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
                val anthropic = config.provider == "anthropic"
                val endpoint = if (anthropic) {
                    config.baseUrl.trimEnd('/') + "/messages"
                } else {
                    config.baseUrl.trimEnd('/') + "/chat/completions"
                }
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

                val body = if (anthropic) anthropicBody(config, messages) else openAiBody(config, messages)
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("API error $code: ${errorText.take(1200)}")
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

    private fun openAiBody(config: ProviderConfig, messages: List<ChatMessage>) = JSONObject().apply {
        put("model", config.model)
        put("stream", true)
        put("messages", JSONArray().apply {
            messages.forEach { message ->
                put(JSONObject().put("role", message.role).put("content", message.content))
            }
        })
    }

    private fun anthropicBody(config: ProviderConfig, messages: List<ChatMessage>) = JSONObject().apply {
        put("model", config.model)
        put("max_tokens", 4096)
        put("stream", true)
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        if (system.isNotBlank()) put("system", system)
        put("messages", JSONArray().apply {
            messages.filter { it.role != "system" }.forEach { message ->
                val role = if (message.role == "assistant") "assistant" else "user"
                put(JSONObject().put("role", role).put("content", message.content))
            }
        })
    }
}
