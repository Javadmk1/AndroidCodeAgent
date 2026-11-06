package com.codeagent.android.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class WorkKind { QUEUED_PROMPT, PLAN_STEP }

data class WorkItem(
    val id: String,
    val projectId: String,
    val title: String,
    val prompt: String,
    val kind: WorkKind,
    val completed: Boolean,
    val createdAt: Long
)

object WorkItemCodec {
    fun encode(items: List<WorkItem>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject()
                .put("id", item.id)
                .put("projectId", item.projectId)
                .put("title", item.title)
                .put("prompt", item.prompt)
                .put("kind", item.kind.name)
                .put("completed", item.completed)
                .put("createdAt", item.createdAt))
        }
    }.toString()

    fun decode(raw: String): List<WorkItem> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val projectId = item.optString("projectId")
            val title = item.optString("title").trim()
            val prompt = item.optString("prompt")
            val kind = runCatching { WorkKind.valueOf(item.optString("kind")) }.getOrNull()
            if (!id.matches(Regex("[a-zA-Z0-9-]{8,64}")) ||
                !projectId.matches(Regex("[a-zA-Z0-9-]{8,64}")) ||
                title.isBlank() || kind == null || title.length > 120 || prompt.length > 12_000
            ) null else WorkItem(id, projectId, title, prompt, kind, item.optBoolean("completed"), item.optLong("createdAt"))
        }.takeLast(MAX_ITEMS)
    }.getOrDefault(emptyList())

    private const val MAX_ITEMS = 300
}

class WorkQueueStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("agent_work_queue", Context.MODE_PRIVATE)

    @Synchronized fun list(projectId: String): List<WorkItem> =
        WorkItemCodec.decode(prefs.getString(KEY, "[]") ?: "[]")
            .filter { it.projectId == projectId }
            .sortedBy { it.createdAt }

    @Synchronized fun addPrompt(projectId: String, prompt: String): WorkItem {
        val text = prompt.trim()
        require(validProject(projectId)) { "شناسه پروژه نامعتبر است." }
        require(text.isNotBlank() && text.length <= 12_000) { "متن درخواست خالی یا بیش از حد طولانی است." }
        val item = WorkItem(UUID.randomUUID().toString(), projectId, text.lineSequence().first().take(80),
            text, WorkKind.QUEUED_PROMPT, false, System.currentTimeMillis())
        append(item)
        return item
    }

    @Synchronized fun addPlanStep(projectId: String, title: String, details: String = ""): WorkItem {
        val safeTitle = title.trim().take(120)
        require(validProject(projectId)) { "شناسه پروژه نامعتبر است." }
        require(safeTitle.isNotBlank()) { "عنوان مرحله را وارد کن." }
        require(details.length <= 12_000) { "توضیحات بیش از حد طولانی است." }
        val item = WorkItem(UUID.randomUUID().toString(), projectId, safeTitle, details.trim(),
            WorkKind.PLAN_STEP, false, System.currentTimeMillis())
        append(item)
        return item
    }

    @Synchronized fun setCompleted(id: String, completed: Boolean) {
        update { item -> if (item.id == id) item.copy(completed = completed) else item }
    }

    @Synchronized fun remove(id: String) {
        save(WorkItemCodec.decode(prefs.getString(KEY, "[]") ?: "[]").filterNot { it.id == id })
    }

    @Synchronized fun readPrompt(id: String): String? =
        WorkItemCodec.decode(prefs.getString(KEY, "[]") ?: "[]")
            .firstOrNull { it.id == id && it.kind == WorkKind.QUEUED_PROMPT }?.prompt

    private fun append(item: WorkItem) {
        val items = WorkItemCodec.decode(prefs.getString(KEY, "[]") ?: "[]")
        require(items.size < 300) { "صف کارها به سقف ۳۰۰ مورد رسیده است." }
        save(items + item)
    }

    private fun update(transform: (WorkItem) -> WorkItem) {
        save(WorkItemCodec.decode(prefs.getString(KEY, "[]") ?: "[]").map(transform))
    }

    private fun save(items: List<WorkItem>) {
        check(prefs.edit().putString(KEY, WorkItemCodec.encode(items.takeLast(300))).commit()) {
            "ذخیره صف کارها ناموفق بود."
        }
    }

    private fun validProject(id: String) = id.matches(Regex("[a-zA-Z0-9-]{8,64}"))

    private companion object { const val KEY = "work_items_v1" }
}
