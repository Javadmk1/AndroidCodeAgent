package com.codeagent.android.llm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small bounded local conversation cache; stored only in app-private preferences. */
class ConversationStore(context: Context) {
    private val prefs = context.getSharedPreferences("conversation_history", Context.MODE_PRIVATE)

    fun load(): List<ChatMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val role = item.optString("role")
                if (role !in setOf("user", "assistant")) null
                else ChatMessage(role, item.optString("content").take(MAX_MESSAGE_CHARS))
            }
        }.getOrDefault(emptyList())
    }

    fun save(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_MESSAGES).forEach { message ->
            if (message.role == "user" || message.role == "assistant") {
                array.put(JSONObject()
                    .put("role", message.role)
                    .put("content", message.content.take(MAX_MESSAGE_CHARS)))
            }
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_MESSAGES).apply()
    }

    private companion object {
        const val KEY_MESSAGES = "messages"
        const val MAX_MESSAGES = 80
        const val MAX_MESSAGE_CHARS = 8_000
    }
}
