package com.codeagent.android.llm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedConversation(val id: String, val title: String, val updatedAt: Long, val messages: List<ChatMessage>)

class MultiConversationStore(context: Context) {
    private val prefs = context.getSharedPreferences("conversation_history", Context.MODE_PRIVATE)
    private val legacy = ConversationStore(context)

    @Synchronized fun loadAll(): List<SavedConversation> {
        val raw = prefs.getString(KEY, null)
        if (raw == null) {
            val old = legacy.load()
            val migrated = SavedConversation(LEGACY_ID, makeTitle(old), System.currentTimeMillis(), old)
            write(listOf(migrated))
            legacy.clear()
            return listOf(migrated)
        }
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optString("id")
                if (id.isBlank()) return@mapNotNull null
                val messages = item.optJSONArray("messages") ?: JSONArray()
                val decoded = (0 until messages.length()).mapNotNull { n ->
                    val msg = messages.optJSONObject(n) ?: return@mapNotNull null
                    val role = msg.optString("role")
                    if (role !in setOf("user", "assistant")) null
                    else ChatMessage(role, msg.optString("content").take(MAX_CHARS))
                }.takeLast(MAX_MESSAGES)
                SavedConversation(id, item.optString("title", DEFAULT_TITLE).take(60),
                    item.optLong("updatedAt"), decoded)
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun save(id: String, title: String, messages: List<ChatMessage>) {
        val all = loadAll().associateBy { it.id }.toMutableMap()
        val safeMessages = messages.filter { it.role == "user" || it.role == "assistant" }
            .takeLast(MAX_MESSAGES).map { it.copy(content = it.content.take(MAX_CHARS)) }
        val resolvedTitle = if (title == DEFAULT_TITLE) makeTitle(safeMessages) else title.trim().take(60)
        all[id] = SavedConversation(id, resolvedTitle.ifBlank { DEFAULT_TITLE },
            System.currentTimeMillis(), safeMessages)
        write(all.values.sortedByDescending { it.updatedAt }.take(MAX_CONVERSATIONS))
    }

    @Synchronized fun create(): SavedConversation {
        val item = SavedConversation(UUID.randomUUID().toString(), DEFAULT_TITLE, System.currentTimeMillis(),
            listOf(ChatMessage("assistant", "سلام. گفتگوی جدید آماده است؛ درخواستت را بنویس.")))
        save(item.id, item.title, item.messages)
        return item
    }

    @Synchronized fun rename(id: String, title: String) {
        val item = loadAll().firstOrNull { it.id == id } ?: return
        save(id, title.trim().ifBlank { DEFAULT_TITLE }, item.messages)
    }

    @Synchronized fun delete(id: String) {
        write(loadAll().filterNot { it.id == id })
    }

    private fun write(items: List<SavedConversation>) {
        val array = JSONArray()
        items.forEach { item ->
            val messages = JSONArray()
            item.messages.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
            array.put(JSONObject().put("id", item.id).put("title", item.title)
                .put("updatedAt", item.updatedAt).put("messages", messages))
        }
        check(prefs.edit().putString(KEY, array.toString()).commit()) { "ذخیره گفتگوها ناموفق بود." }
    }

    private fun makeTitle(messages: List<ChatMessage>): String =
        messages.firstOrNull { it.role == "user" }?.content?.lineSequence()?.firstOrNull()
            ?.trim()?.take(36)?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE

    private companion object {
        const val KEY = "conversations_v2"
        const val LEGACY_ID = "legacy-default"
        const val DEFAULT_TITLE = "گفتگوی جدید"
        const val MAX_CONVERSATIONS = 100
        const val MAX_MESSAGES = 80
        const val MAX_CHARS = 8_000
    }
}
