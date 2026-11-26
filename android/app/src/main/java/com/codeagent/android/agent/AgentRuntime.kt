package com.codeagent.android.agent

import com.codeagent.android.llm.ProviderConfig
import com.codeagent.android.llm.ApiEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Bounded tool loop. Text deltas are forwarded while the model response is arriving. */
class AgentRuntime(
    private val registry: ToolRegistry,
    private val maxToolRounds: Int = 4
) {
    data class Result(val answer: String, val toolLog: List<String>)

    suspend fun run(
        config: ProviderConfig,
        apiKey: String,
        conversation: List<Pair<String, String>>,
        approveWrite: suspend (String, String) -> Boolean,
        onText: suspend (String) -> Unit = {},
        onActivity: suspend (String) -> Unit = {},
        approveBuild: suspend (String) -> Boolean = { false }
    ): Result = withContext(Dispatchers.IO) {
        val anthropic = config.provider == "anthropic"
        val messages = JSONArray()
        conversation.filter { it.first != "system" }.forEach { (role, content) ->
            val providerContent = multimodalContent(content, anthropic)
            messages.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user").put("content", providerContent))
        }
        val system = conversation.filter { it.first == "system" }.joinToString("\n") { it.second }
        val logs = mutableListOf<String>()

        repeat(maxToolRounds) { round ->
            onActivity("درخواست به مدل ارسال شد · مرحله ${round + 1}")
            val response = request(config, apiKey, messages, anthropic, system, onText)
            if (anthropic) {
                val blocks = response.optJSONArray("content") ?: JSONArray()
                val toolUses = (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                    .filter { it.optString("type") == "tool_use" }
                if (toolUses.isEmpty()) {
                    val answer = (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                        .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                    return@withContext Result(answer, logs)
                }
                messages.put(JSONObject().put("role", "assistant").put("content", blocks))
                val results = JSONArray()
                for (use in toolUses) {
                    val name = use.optString("name")
                    val args = use.optJSONObject("input") ?: JSONObject()
                    onActivity("اجرای ابزار: $name")
                    val result = registry.execute(name, args, approveWrite, approveBuild, onActivity)
                    val logEntry = "$name: ${if (result.optBoolean("ok")) "انجام شد" else result.optString("error", "رد شد")}"
                    logs += logEntry
                    onActivity(logEntry)
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
                    onActivity("اجرای ابزار: $name")
                    val result = registry.execute(name, args, approveWrite, approveBuild, onActivity)
                    val logEntry = "$name: ${if (result.optBoolean("ok")) "انجام شد" else result.optString("error", "رد شد")}"
                    logs += logEntry
                    onActivity(logEntry)
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", result.toString()))
                }
            }
        }

        val finalResponse = request(config, apiKey, messages, anthropic, system, onText)
        val answer = if (anthropic) {
            val blocks = finalResponse.optJSONArray("content") ?: JSONArray()
            (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        } else {
            finalResponse.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }
        Result(answer.ifBlank { "سقف اجرای ابزارها تمام شد؛ درخواست را دقیق‌تر و مرحله‌ای‌تر بنویس." }, logs)
    }


    private fun multimodalContent(raw: String, anthropic: Boolean): Any {
        var remaining = raw
        val images = mutableListOf<Pair<String, String>>()
        while (true) {
            val start = remaining.indexOf("[[IMAGE:")
            if (start < 0) break
            val end = remaining.indexOf("]]", start)
            if (end < 0) break
            val payload = remaining.substring(start + 8, end)
            val separator = payload.indexOf(':')
            if (separator > 0) {
                val mime = payload.substring(0, separator)
                val data = payload.substring(separator + 1)
                if (mime.startsWith("image/") && data.isNotBlank()) images += mime to data
            }
            remaining = remaining.removeRange(start, end + 2)
        }
        if (images.isEmpty()) return raw
        val blocks = JSONArray()
        if (remaining.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", remaining.trim()))
        images.forEach { (mime, data) ->
            if (anthropic) {
                blocks.put(JSONObject().put("type", "image").put("source",
                    JSONObject().put("type", "base64").put("media_type", mime).put("data", data)))
            } else {
                blocks.put(JSONObject().put("type", "image_url").put("image_url",
                    JSONObject().put("url", "data:$mime;base64,$data")))
            }
        }
        return blocks
    }

    private suspend fun request(
        config: ProviderConfig,
        apiKey: String,
        messages: JSONArray,
        anthropic: Boolean,
        system: String,
        onText: suspend (String) -> Unit
    ): JSONObject {
        val body = if (anthropic) {
            JSONObject().put("model", config.model).put("max_tokens", 4096)
                .put("stream", true).put("messages", messages).put("tools", anthropicTools())
                .apply { if (system.isNotBlank()) put("system", system) }
        } else {
            val openAiMessages = JSONArray()
            if (system.isNotBlank()) openAiMessages.put(JSONObject().put("role", "system").put("content", system))
            for (index in 0 until messages.length()) openAiMessages.put(messages.get(index))
            JSONObject().put("model", config.model).put("stream", true)
                .put("messages", openAiMessages).put("tools", openAiTools()).put("tool_choice", "auto")
        }
        val endpoint = ApiEndpoint.chat(config)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        val cancellationHandle = currentCoroutineContext()[Job]?.let { job ->
            disconnectOnCancellation(connection, job)
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            if (anthropic) {
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
            } else connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val detail = runCatching {
                    val json = JSONObject(errorText)
                    json.optJSONObject("error")?.optString("message")
                        ?: json.optString("message").takeIf { it.isNotBlank() } ?: errorText
                }.getOrDefault(errorText)
                val hint = when (code) {
                    401, 403 -> "کلید API یا مجوز دسترسی نامعتبر است."
                    404 -> "آدرس endpoint یا نام مدل پیدا نشد."
                    429 -> "سهمیه یا محدودیت درخواست سرویس پر شده است."
                    400 -> "ساختار درخواست یا قابلیت ابزارهای مدل پذیرفته نشد."
                    else -> "درخواست به سرویس مدل ناموفق بود."
                }
                error("HTTP " + code + " · " + hint + " " + detail.take(900))
            }
            val contentType = connection.contentType.orEmpty()
            if (contentType.contains("application/json", ignoreCase = true) ||
                contentType.contains("application/problem+json", ignoreCase = true)) {
                val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = runCatching { JSONObject(raw) }.getOrElse {
                    throw IllegalStateException("پاسخ API نه SSE معتبر بود و نه JSON؛ Content-Type: " + contentType)
                }
                val responseText = if (anthropic) {
                    val blocks = json.optJSONArray("content") ?: JSONArray()
                    (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                        .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                } else {
                    val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    val content = message?.opt("content")
                    when (content) {
                        is String -> content
                        is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it) }
                            .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                        else -> ""
                    }
                }
                if (responseText.isNotEmpty()) onText(responseText)
                json
            } else if (anthropic) readAnthropicStream(connection, onText) else readOpenAiStream(connection, onText)
        } catch (error: Exception) {
            // A disconnect triggered by cancellation can surface as an IOException. Preserve
            // cancellation semantics instead of reporting it as an ordinary provider failure.
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            cancellationHandle?.dispose()
            connection.disconnect()
        }
    }

    private suspend fun readOpenAiStream(
        connection: HttpURLConnection,
        onText: suspend (String) -> Unit
    ): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", "")
        val text = StringBuilder()
        val calls = sortedMapOf<Int, JSONObject>()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val chunk = runCatching { JSONObject(payload) }.getOrElse {
                    throw IllegalStateException("پاسخ جریانی OpenAI-compatible خراب است.")
                }
                if (chunk.has("error")) throw IllegalStateException(
                    chunk.optJSONObject("error")?.optString("message") ?: chunk.optString("error")
                )
                val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val rawContent = delta.opt("content")
                val content = when (rawContent) {
                    is String -> rawContent
                    is JSONArray -> (0 until rawContent.length()).mapNotNull { rawContent.optJSONObject(it) }
                        .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                    else -> ""
                }
                if (content.isNotEmpty()) {
                    text.append(content)
                    onText(content)
                }
                val toolDeltas = delta.optJSONArray("tool_calls") ?: continue
                for (i in 0 until toolDeltas.length()) {
                    val part = toolDeltas.optJSONObject(i) ?: continue
                    val index = part.optInt("index", i)
                    val call = calls.getOrPut(index) {
                        JSONObject().put("id", "").put("type", "function")
                            .put("function", JSONObject().put("name", "").put("arguments", ""))
                    }
                    if (part.has("id")) call.put("id", call.optString("id") + part.optString("id"))
                    val fn = part.optJSONObject("function")
                    if (fn != null) {
                        val target = call.getJSONObject("function")
                        if (fn.has("name")) target.put("name", target.optString("name") + fn.optString("name"))
                        if (fn.has("arguments")) target.put("arguments", target.optString("arguments") + fn.optString("arguments"))
                    }
                }
            }
        }
        message.put("content", text.toString())
        if (calls.isNotEmpty()) {
            val array = JSONArray()
            calls.values.forEach { call ->
                val fn = call.getJSONObject("function")
                val raw = fn.optString("arguments", "{}")
                // Validate the accumulated arguments before passing them to the tool dispatcher.
                runCatching { JSONObject(raw) }.getOrElse { throw IllegalStateException("Invalid streamed tool arguments for ${fn.optString("name")}.") }
                array.put(call)
            }
            message.put("tool_calls", array)
        }
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message)))
    }

    private suspend fun readAnthropicStream(
        connection: HttpURLConnection,
        onText: suspend (String) -> Unit
    ): JSONObject {
        val blocks = sortedMapOf<Int, JSONObject>()
        val partialInputs = mutableMapOf<Int, StringBuilder>()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            var eventName = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("event:")) {
                    eventName = line.removePrefix("event:").trim()
                    continue
                }
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val event = runCatching { JSONObject(payload) }.getOrElse {
                    throw IllegalStateException("پاسخ جریانی Anthropic خراب است.")
                }
                if (event.optString("type", eventName) == "error" || event.has("error")) {
                    val error = event.optJSONObject("error")
                    throw IllegalStateException(error?.optString("message") ?: event.optString("message", "خطای سرویس Anthropic"))
                }
                when (event.optString("type", eventName)) {
                    "content_block_start" -> {
                        val index = event.optInt("index")
                        val block = event.optJSONObject("content_block") ?: JSONObject()
                        blocks[index] = JSONObject(block.toString())
                    }
                    "content_block_delta" -> {
                        val index = event.optInt("index")
                        val delta = event.optJSONObject("delta") ?: continue
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val value = delta.optString("text")
                                val block = blocks.getOrPut(index) { JSONObject().put("type", "text").put("text", "") }
                                block.put("text", block.optString("text") + value)
                                if (value.isNotEmpty()) onText(value)
                            }
                            "input_json_delta" -> partialInputs.getOrPut(index) { StringBuilder() }
                                .append(delta.optString("partial_json"))
                        }
                    }
                    "content_block_stop" -> {
                        val index = event.optInt("index")
                        val raw = partialInputs.remove(index)?.toString()
                        if (raw != null) {
                            val block = blocks[index] ?: JSONObject()
                            val parsedInput = runCatching { JSONObject(raw) }.getOrElse {
                                throw IllegalStateException("Invalid streamed Anthropic tool input for ${block.optString("name")}.")
                            }
                            block.put("input", parsedInput)
                            blocks[index] = block
                        }
                    }
                }
                eventName = ""
            }
        }
        val content = JSONArray()
        blocks.values.forEach { content.put(it) }
        return JSONObject().put("content", content)
    }

    private fun schemas(): List<Triple<String, String, JSONObject>> = listOf(
        Triple("list_files", "List files and folders inside the project workspace.",
            JSONObject("""{"type":"object","properties":{"path":{"type":"string"}}}""")),
        Triple("read_file", "Read a UTF-8 text file from the project workspace.",
            JSONObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")),
        Triple("search_in_files", "Search text inside project files.",
            JSONObject("""{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string"}},"required":["query"]}""")),
        Triple("diff_file", "Compare proposed file content with the current workspace version. Use this before requesting an approved write.",
            JSONObject("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""")),
        Triple("undo_file", "Restore the last saved version of a file. Show the exact reverse diff and require user approval before restoring.",
            JSONObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")),
        Triple("create_plan_step", "Add one concrete, testable step to the current project's saved implementation plan.",
            JSONObject("""{"type":"object","properties":{"title":{"type":"string"},"details":{"type":"string"}},"required":["title"]}""")),
        Triple("queue_request", "Save a future user-requested task in the project's persistent queue. Use only when the user explicitly asks to queue or defer work.",
            JSONObject("""{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}""")),
        Triple("write_file", "Create or replace a UTF-8 text file. User approval is required.",
            JSONObject("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""")),
        Triple("build_project", "Run an approved Gradle task in the isolated Android SDK build service. Use assembleDebug to produce an APK. User approval is required.",
            JSONObject("""{"type":"object","properties":{"task":{"type":"string","enum":["assembleDebug","testDebugUnitTest","lintDebug","check","tasks","clean"]}}}"""))
    )

    private fun anthropicTools(): JSONArray = JSONArray().apply {
        schemas().forEach { (name, description, schema) ->
            put(JSONObject().put("name", name).put("description", description).put("input_schema", schema))
        }
    }

    private fun openAiTools(): JSONArray = JSONArray().apply {
        schemas().forEach { (name, description, schema) ->
            put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", name).put("description", description).put("parameters", schema)))
        }
    }
}
