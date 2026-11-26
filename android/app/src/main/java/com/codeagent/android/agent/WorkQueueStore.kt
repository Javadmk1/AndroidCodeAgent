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

    /** Pure filtering helpers keep project lifecycle cleanup deterministic and testable. */
    fun withoutProject(items: List<WorkItem>, projectId: String): List<WorkItem> =
        items.filterNot { it.projectId == projectId }

    fun forProjects(items: List<WorkItem>, projectIds: Set<String>): List<WorkItem> =
        items.filter { it.projectId in projectIds }

    private const val MAX_ITEMS = 300
}

/**
 * All instances share one process-wide lock. Instance-level @Synchronized is not
 * sufficient because ProjectStore and the UI create separate WorkQueueStore objects.
 */
class WorkQueueStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("agent_work_queue", Context.MODE_PRIVATE)

    fun list(projectId: String): List<WorkItem> = locked {
        WorkItemCodec.decode(prefs.getString(KEY, "[]") ?: "[]")
            .filter { it.projectId == projectId }
            .sortedBy { it.createdAt }
    }

    fun addPrompt(projectId: String, prompt: String): WorkItem = locked {
        val text = prompt.trim()
        require(validProject(projectId)) { "شناسه پروژه نامعتبر است." }
        require(text.isNotBlank() && text.length <= 12_000) { "متن درخواست خالی یا بیش از حد طولانی است." }
        val item = WorkItem(UUID.randomUUID().toString(), projectId, text.lineSequence().first().take(80),
            text, WorkKind.QUEUED_PROMPT, false, System.currentTimeMillis())
        append(item)
        item
    }

    fun addPlanStep(projectId: String, title: String, details: String = ""): WorkItem = locked {
        val safeTitle = title.trim().take(120)
        require(validProject(projectId)) { "شناسه پروژه نامعتبر است." }
        require(safeTitle.isNotBlank()) { "عنوان مرحله را وارد کن." }
        require(details.length <= 12_000) { "توضیحات بیش از حد طولانی است." }
        val item = WorkItem(UUID.randomUUID().toString(), projectId, safeTitle, details.trim(),
            WorkKind.PLAN_STEP, false, System.currentTimeMillis())
        append(item)
        item
    }

    /** Removes every queued prompt and plan step owned by a deleted project. */
    fun removeProject(projectId: String) = locked {
        require(validProject(projectId)) { "شناسه پروژه نامعتبر است." }
        val current = WorkItemCodec.decode(prefs.getString(KEY, "[]") ?: "[]")
        save(WorkItemCodec.withoutProject(current, projectId))
    }

    /** Startup reconciliation removes queue entries whose project metadata no longer exists. */
    fun retainProjects(projectIds: Set<String>) = locked {
        val current = WorkItemCodec.decode(prefs.getString(KEY, "[]") ?: "[]")
        save(WorkItemCodec.forProjects(current, projectIds))
    }

    fun setCompleted(id: String, completed: Boolean) = locked {
        update { item -> if (item.id == id) item.copy(completed = completed) else item }
    }

    fun remove(id: String) = locked {
        save(WorkItemCodec.decode(prefs.getString(KEY, "[]") ?: "[]").filterNot { it.id == id })
    }

    fun readPrompt(id: String): String? = locked {
        WorkItemCodec.decode(prefs.getString(KEY, "[]") ?: "[]")
            .firstOrNull { it.id == id && it.kind == WorkKind.QUEUED_PROMPT }?.prompt
    }

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

    private inline fun <T> locked(block: () -> T): T = synchronized(LOCK, block)

    private companion object {
        const val KEY = "work_items_v1"
        val LOCK = Any()
    }
}
