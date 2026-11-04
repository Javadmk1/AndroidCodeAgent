package com.codeagent.android.agent

import com.codeagent.android.llm.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded OpenAI-compatible tool loop.
 * Only registered workspace tools are exposed; writes are gated by the UI approval callback.
 */
class AgentRuntime(
    private val registry: ToolRegistry,
    private val maxToolRounds: Int = 4
) {
    data class Result(val answer: String, val toolLog: List<String>)

    suspend fun run(
        config: ProviderConfig,
        apiKey: String,
        conversation: List<Pair<String, String>>,
        approveWrite: suspend (String) -> Boolean
    ): Result = withContext(Dispatchers.IO) {
        require(config.provider != "anthropic") {
            "Tool execution currently requires an OpenAI-compatible provider."
        }
        val messages = JSONArray()
        conversation.forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        val logs = mutableListOf<String>()

        repeat(maxToolRounds) {
            val response = request(config, apiKey, messages)
            val choice = response.optJSONArray("choices")?.optJSONObject(0)
                ?: error("Provider returned no choices")
            val assistant = choice.optJSONObject("message") ?: error("Provider response has no message")
            val calls = assistant.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return@withContext Result(assistant.optString("content"), logs)
            }
            messages.put(assistant)
            for (index in 0 until calls.length()) {
                val call = calls.getJSONObject(index)
                val id = call.optString("id")
                val function = call.optJSONObject("function") ?: error("Malformed tool call")
                val name = function.optString("name")
                val args = JSONObject(function.optString("arguments", "{}"))
                val result = registry.execute(name, args, approveWrite)
                logs += "$name: ${if (result.optBoolean("ok")) "انجام شد" else result.optString("error", "رد شد")}"
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result.toString())
                )
            }
        }
        val finalResponse = request(config, apiKey, messages)
        val finalMessage = finalResponse.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")
        Result(
            finalMessage?.optString("content").orEmpty().ifBlank {
                "برای جلوگیری از اجرای زنجیرهٔ طولانی، سقف اجرای ابزارها متوقف شد. درخواست را دقیق‌تر کن."
            },
            logs
        )
    }

    private fun request(
        config: ProviderConfig,
        apiKey: String,
        messages: JSONArray
    ): JSONObject {
        val tools = JSONArray().apply {
            put(tool("list_files", "List files and folders inside the project workspace.", """{"type":"object","properties":{"path":{"type":"string"}}}"""))
            put(tool("read_file", "Read a UTF-8 text file from the project workspace.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""))
            put(tool("search_in_files", "Search text inside project files.", """{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string"}},"required":["query"]}"""))
            put(tool("write_file", "Create or replace a UTF-8 text file. User approval is required.", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}"""))
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("stream", false)
            .put("messages", messages)
            .put("tools", tools)
            .put("tool_choice", "auto")
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("API error $code: ${text.take(1000)}")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun tool(name: String, description: String, schema: String) =
        JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", JSONObject(schema))
            )
}
