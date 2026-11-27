package com.codeagent.android.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts the app's local attachment marker into the provider-specific image
 * payload. The marker is an internal transport detail and is never sent as text.
 */
internal object LlmRequestBodyBuilder {
    private const val MAX_IMAGE_BASE64_CHARS = 6 * 1024 * 1024
    private val supportedImageTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

    fun openAiBody(config: ProviderConfig, messages: List<ChatMessage>): JSONObject =
        JSONObject().apply {
            put("model", config.model)
            put("stream", true)
            put("messages", JSONArray().apply {
                messages.forEach { message ->
                    val image = if (message.role == "user") imageFrom(message.content) else null
                    val content: Any = if (image == null) message.content else JSONArray().apply {
                        if (image.first.isNotBlank()) put(JSONObject().put("type", "text").put("text", image.first))
                        put(JSONObject().put("type", "image_url").put(
                            "image_url", JSONObject().put("url", "data:${image.second};base64,${image.third}")
                        ))
                    }
                    put(JSONObject().put("role", message.role).put("content", content))
                }
            })
        }

    fun anthropicBody(config: ProviderConfig, messages: List<ChatMessage>): JSONObject =
        JSONObject().apply {
            put("model", config.model)
            put("max_tokens", 4096)
            put("stream", true)
            val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
            if (system.isNotBlank()) put("system", system)
            put("messages", JSONArray().apply {
                messages.filter { it.role != "system" }.forEach { message ->
                    val role = if (message.role == "assistant") "assistant" else "user"
                    val image = if (role == "user") imageFrom(message.content) else null
                    val content: Any = if (image == null) message.content else JSONArray().apply {
                        if (image.first.isNotBlank()) put(JSONObject().put("type", "text").put("text", image.first))
                        put(JSONObject().put("type", "image").put(
                            "source", JSONObject()
                                .put("type", "base64")
                                .put("media_type", image.second)
                                .put("data", image.third)
                        ))
                    }
                    put(JSONObject().put("role", role).put("content", content))
                }
            })
        }

    private fun imageFrom(content: String): Triple<String, String, String>? {
        val markerStart = content.indexOf("[[IMAGE:")
        if (markerStart < 0) return null
        val markerEnd = content.indexOf("]]", markerStart + 8)
        require(markerEnd >= 0) { "داده پیوست تصویر ناقص است." }
        val payload = content.substring(markerStart + 8, markerEnd)
        val separator = payload.indexOf(':')
        require(separator > 0) { "قالب پیوست تصویر معتبر نیست." }
        val mime = payload.substring(0, separator).lowercase()
        val data = payload.substring(separator + 1)
        require(mime in supportedImageTypes) { "قالب تصویر پشتیبانی نمی‌شود: $mime" }
        require(data.isNotBlank() && data.length <= MAX_IMAGE_BASE64_CHARS &&
            data.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
            "حجم تصویر از حد مجاز بیشتر است یا داده تصویر معتبر نیست."
        }
        val text = content.removeRange(markerStart, markerEnd + 2)
        return Triple(text.trim(), mime, data)
    }
}
