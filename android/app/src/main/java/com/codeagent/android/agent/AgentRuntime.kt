package com.codeagent.android.agent

import com.codeagent.android.llm.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Bounded tool loop for OpenAI-compatible and Anthropic Messages APIs. */
class AgentRuntime(
    private val registry: ToolRegistry,
    private val maxToolRounds: Int = 4
) {
    data class Result(val answer: String, val toolLog: List<String>)

    suspend fun run(
        config: ProviderConfig,
        apiKey: String,
        conversation: List<Pair<String, String>>,
        approveWrite: suspend (String, String) -> Boolean
    ): Result = withContext(Dispatchers.IO) {
        val anthropic = config.provider == "anthropic"
        val messages = JSONArray()
        conversation.filter { it.first != "system" }.forEach { (role, content) ->
            messages.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user").put("content", content))
        }
        val system = conversation.filter { it.first == "system" }.joinToString("\n") { it.second }
        val logs = mutableListOf<String>()

        repeat(maxToolRounds) {
            val response = request(config, apiKey, messages, anthropic, system)
            if (anthropic) {
                val blocks = response.optJSONArray("content") ?: JSONArray()
                val toolUses = (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                    .filter { it.optString("type") == "tool_use" }
                if (toolUses.isEmpty()) {
                    val answer = (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                        .filter { it.optString("type") == "text" }
                        .joinToString("") { it.optString("text") }
                    return@withContext Result(answer, logs)
                }
                messages.put(JSONObject().put("role", "assistant").put("content", blocks))
                val results = JSONArray()
                for (use in toolUses) {
                    val name = use.optString("name")
                    val args = use.optJSONObject("input") ?: JSONObject()
                    val result = registry.execute(name, args, approveWrite)
                    logs += "$name: ${if (result.optBoolean("ok")) "انجام شد" else result.optString("error", "رد شد")}"
                    results.put(JSONObject().put("type", "tool_result").put("tool_use_id", use.optString("id")).put("content", result.toString()))
                }
                messages.put(JSONObject().put("role", "user").put("content", results))
            } else {
                val assistant = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?: error("Provider returned no message")
                val calls = assistant.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    return@withContext Result(assistant.optString("content"), logs)
                }
                messages.put(assistant)
                for (index in 0 until calls.length()) {
                    val call = calls.getJSONObject(index)
                    val function = call.optJSONObject("function") ?: error("Malformed tool call")
                    val name = function.optString("name")
                    val args = JSONObject(function.optString("arguments", "{}"))
                    val result = registry.execute(name, args, approveWrite)
                    logs += "$name: ${if (result.optBoolean("ok")) "انجام شد" else result.optString("error", "رد شد")}"
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", result.toString()))
                }
            }
        }

        val finalResponse = request(config, apiKey, messages, anthropic, system)
        val answer = if (anthropic) {
            val blocks = finalResponse.optJSONArray("content") ?: JSONArray()
            (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        } else {
            finalResponse.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }
        Result(answer.ifBlank { "سقف اجرای ابزارها تمام شد؛ درخواست را دقیق‌تر و مرحله‌ای‌تر بنویس." }, logs)
    }

    private fun request(
        config: ProviderConfig,
        apiKey: String,
        messages: JSONArray,
        anthropic: Boolean,
        system: String
    ): JSONObject {
        val tools = toolDefinitions()
        val body = if (anthropic) {
            JSONObject().put("model", config.model).put("max_tokens", 4096)
                .put("messages", messages).put("tools", tools)
                .apply { if (system.isNotBlank()) put("system", system) }
        } else {
            val openAiMessages = JSONArray()
            if (system.isNotBlank()) openAiMessages.put(JSONObject().put("role", "system").put("content", system))
            for (index in 0 until messages.length()) openAiMessages.put(messages.get(index))
            JSONObject().put("model", config.model).put("stream", false)
                .put("messages", openAiMessages).put("tools", openAiTools()).put("tool_choice", "auto")
        }
        val endpoint = config.baseUrl.trimEnd('/') + if (anthropic) "/messages" else "/chat/completions"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (anthropic) {
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
            } else connection.setRequestProperty("Authorization", "Bearer $apiKey")
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

    private fun toolDefinitions(): JSONArray {
        val definitions = listOf(
            Triple("list_files", "List files and folders inside the project workspace.", """{"type":"object","properties":{"path":{"type":"string"}}}"""),
            Triple("read_file", "Read a UTF-8 text file from the project workspace.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
            Triple("search_in_files", "Search text inside project files.", """{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string"}},"required":["query"]}"""),
            Triple("write_file", "Create or replace a UTF-8 text file. User approval is required.", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""")
        )
        return JSONArray().apply {
            definitions.forEach { (name, description, schema) ->
                val parameters = JSONObject(schema)
                put(JSONObject().apply {
                    if (name.isNotBlank()) put("name", name)
                    if (description.isNotBlank()) put("description", description)
                    if (name.isNotBlank()) put("input_schema", parameters)
                    put("type", "function")
                    put("function", JSONObject().put("name", name).put("description", description).put("parameters", parameters))
                })
            }
        }.let { all ->
            // Anthropic expects only name/description/input_schema; OpenAI expects type/function.
            JSONArray().apply {
                for (i in 0 until all.length()) {
                    val item = all.getJSONObject(i)
                    put(JSONObject().put("name", item.getString("name"))
                        .put("description", item.getString("description"))
                        .put("input_schema", item.getJSONObject("input_schema")))
                }
            }
        }
    }

    private fun openAiTools(): JSONArray {
        val definitions = listOf(
            Triple("list_files", "List files and folders inside the project workspace.", """{"type":"object","properties":{"path":{"type":"string"}}}"""),
            Triple("read_file", "Read a UTF-8 text file from the project workspace.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
            Triple("search_in_files", "Search text inside project files.", """{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string"}},"required":["query"]}"""),
            Triple("write_file", "Create or replace a UTF-8 text file. User approval is required.", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""")
        )
        return JSONArray().apply {
            definitions.forEach { (name, description, schema) ->
                put(JSONObject().put("type", "function").put("function",
                    JSONObject().put("name", name).put("description", description).put("parameters", JSONObject(schema))))
            }
        }
    }
}
