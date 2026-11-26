package com.codeagent.android.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TaskExecutionStatus {
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

data class TaskExecutionRecord(
    val id: String,
    val projectId: String,
    val prompt: String,
    val status: TaskExecutionStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val result: String = "",
    val error: String = ""
)

object TaskExecutionCodec {
    private const val MAX_RECORDS = 100

    fun encode(records: List<TaskExecutionRecord>): String = JSONArray().apply {
        records.takeLast(MAX_RECORDS).forEach { record ->
            put(JSONObject()
                .put("id", record.id)
                .put("projectId", record.projectId)
                .put("prompt", record.prompt)
                .put("status", record.status.name)
                .put("createdAt", record.createdAt)
                .put("updatedAt", record.updatedAt)
                .put("result", record.result.take(12_000))
                .put("error", record.error.take(1_000)))
        }
    }.toString()

    fun decode(raw: String): List<TaskExecutionRecord> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val projectId = item.optString("projectId")
            val prompt = item.optString("prompt")
            val status = runCatching { TaskExecutionStatus.valueOf(item.optString("status")) }.getOrNull()
            if (!validId(id) || !validId(projectId) || prompt.isBlank() ||
                prompt.length > 12_000 || status == null
            ) null else TaskExecutionRecord(
                id = id,
                projectId = projectId,
                prompt = prompt,
                status = status,
                createdAt = item.optLong("createdAt"),
                updatedAt = item.optLong("updatedAt"),
                result = item.optString("result").take(12_000),
                error = item.optString("error").take(1_000)
            )
        }.takeLast(MAX_RECORDS)
    }.getOrDefault(emptyList())

    fun recoverInterrupted(records: List<TaskExecutionRecord>, now: Long): List<TaskExecutionRecord> =
        records.map { record ->
            if (record.status == TaskExecutionStatus.RUNNING ||
                record.status == TaskExecutionStatus.WAITING_APPROVAL
            ) record.copy(
                status = TaskExecutionStatus.INTERRUPTED,
                updatedAt = now,
                error = "برنامه پیش از پایان عملیات بسته شد؛ برای ادامه، درخواست را دوباره بررسی و اجرا کن."
            ) else record
        }

    fun withoutProject(records: List<TaskExecutionRecord>, projectId: String): List<TaskExecutionRecord> =
        records.filterNot { it.projectId == projectId }

    fun forProjects(records: List<TaskExecutionRecord>, projectIds: Set<String>): List<TaskExecutionRecord> =
        records.filter { it.projectId in projectIds }

    private fun validId(value: String): Boolean = value.matches(Regex("[a-zA-Z0-9-]{8,64}"))
}

class TaskExecutionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("agent_task_executions", Context.MODE_PRIVATE)

    init {
        locked {
            if (!recoveryDone) {
                val current = readAll()
                val recovered = TaskExecutionCodec.recoverInterrupted(current, System.currentTimeMillis())
                if (recovered != current) save(recovered)
                recoveryDone = true
            }
        }
    }

    fun list(projectId: String): List<TaskExecutionRecord> = locked {
        readAll().filter { it.projectId == projectId }.sortedByDescending { it.createdAt }
    }

    fun start(projectId: String, prompt: String): TaskExecutionRecord = locked {
        require(validId(projectId)) { "شناسه پروژه نامعتبر است." }
        val safePrompt = prompt.trim()
        require(safePrompt.isNotBlank() && safePrompt.length <= 12_000) { "درخواست نامعتبر است." }
        val records = readAll()
        require(records.none {
            it.projectId == projectId &&
                (it.status == TaskExecutionStatus.RUNNING || it.status == TaskExecutionStatus.WAITING_APPROVAL)
        }) { "برای این پروژه یک عملیات Agent هنوز فعال است." }
        val now = System.currentTimeMillis()
        val record = TaskExecutionRecord(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            prompt = safePrompt,
            status = TaskExecutionStatus.RUNNING,
            createdAt = now,
            updatedAt = now
        )
        save((records + record).takeLast(MAX_RECORDS))
        record
    }

    fun update(
        id: String,
        status: TaskExecutionStatus,
        result: String = "",
        error: String = ""
    ) = locked {
        val now = System.currentTimeMillis()
        val records = readAll()
        require(records.any { it.id == id }) { "سابقه اجرای وظیفه پیدا نشد." }
        save(records.map { record ->
            if (record.id == id) record.copy(
                status = status,
                updatedAt = now,
                result = result.take(12_000),
                error = error.take(1_000)
            ) else record
        })
    }

    fun removeProject(projectId: String) = locked {
        save(TaskExecutionCodec.withoutProject(readAll(), projectId))
    }

    fun retainProjects(projectIds: Set<String>) = locked {
        save(TaskExecutionCodec.forProjects(readAll(), projectIds))
    }

    private fun readAll(): List<TaskExecutionRecord> =
        TaskExecutionCodec.decode(prefs.getString(KEY, "[]") ?: "[]")

    private fun save(records: List<TaskExecutionRecord>) {
        check(prefs.edit().putString(KEY, TaskExecutionCodec.encode(records)).commit()) {
            "ذخیره وضعیت عملیات Agent ناموفق بود."
        }
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(LOCK, block)

    private fun validId(value: String): Boolean =
        value.matches(Regex("[a-zA-Z0-9-]{8,64}"))

    private companion object {
        const val KEY = "execution_records_v1"
        const val MAX_RECORDS = 100
        val LOCK = Any()
        var recoveryDone = false
    }
}
