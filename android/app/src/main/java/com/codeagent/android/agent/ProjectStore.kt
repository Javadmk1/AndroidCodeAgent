package com.codeagent.android.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ProjectRecord(val id: String, val name: String, val updatedAt: Long)

class ProjectStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("project_store", Context.MODE_PRIVATE)
    private val root = File(app.filesDir, "projects")

    init {
        check(root.exists() || root.mkdirs()) { "ساخت فضای پروژه‌ها ناموفق بود." }
        synchronized(this) {
            if (prefs.getString(KEY_PROJECTS, null) == null) {
                val legacy = File(app.filesDir, "workspace")
                val initial = File(root, DEFAULT_ID)
                if (!initial.exists()) {
                    if (legacy.isDirectory) {
                        check(legacy.renameTo(initial) || copyDirectory(legacy, initial)) { "انتقال پروژه قبلی ناموفق بود." }
                    } else check(initial.mkdirs()) { "ساخت پروژه پیش‌فرض ناموفق بود." }
                }
                write(listOf(ProjectRecord(DEFAULT_ID, "پروژه من", System.currentTimeMillis())))
                prefs.edit().putString(KEY_ACTIVE, DEFAULT_ID).commit()
            }
            val items = loadProjects()
            if (items.isEmpty()) {
                val fallback = File(root, DEFAULT_ID)
                check(fallback.exists() || fallback.mkdirs())
                write(listOf(ProjectRecord(DEFAULT_ID, "پروژه من", System.currentTimeMillis())))
                prefs.edit().putString(KEY_ACTIVE, DEFAULT_ID).commit()
            } else if (items.none { it.id == activeProjectId() }) {
                prefs.edit().putString(KEY_ACTIVE, items.first().id).commit()
            }
        }
    }

    @Synchronized fun loadProjects(): List<ProjectRecord> {
        val raw = prefs.getString(KEY_PROJECTS, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val name = item.optString("name").trim()
                if (!id.matches(Regex("[a-zA-Z0-9-]{8,64}")) || name.isBlank()) null
                else ProjectRecord(id, name.take(60), item.optLong("updatedAt"))
            }.filter { directory(it.id).isDirectory }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun activeProjectId(): String = prefs.getString(KEY_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID

    fun directory(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{8,64}"))) { "شناسه پروژه نامعتبر است." }
        val base = root.canonicalFile
        val result = File(base, id).canonicalFile
        require(result.parentFile == base) { "مسیر پروژه نامعتبر است." }
        return result
    }

    @Synchronized fun create(name: String): ProjectRecord {
        val safe = name.trim().take(60)
        require(safe.isNotBlank()) { "نام پروژه را وارد کن." }
        val id = UUID.randomUUID().toString()
        check(directory(id).mkdirs()) { "ساخت پوشه پروژه ناموفق بود." }
        val item = ProjectRecord(id, safe, System.currentTimeMillis())
        write(loadProjects() + item)
        check(prefs.edit().putString(KEY_ACTIVE, id).commit())
        return item
    }

    @Synchronized fun rename(id: String, name: String) {
        val safe = name.trim().take(60)
        require(safe.isNotBlank()) { "نام پروژه را وارد کن." }
        val items = loadProjects().map { if (it.id == id) it.copy(name = safe, updatedAt = System.currentTimeMillis()) else it }
        require(items.any { it.id == id }) { "پروژه پیدا نشد." }
        write(items)
    }

    @Synchronized fun activate(id: String) {
        require(loadProjects().any { it.id == id }) { "پروژه پیدا نشد." }
        check(prefs.edit().putString(KEY_ACTIVE, id).commit()) { "ذخیره پروژه فعال ناموفق بود." }
    }

    @Synchronized fun delete(id: String) {
        val items = loadProjects()
        require(items.size > 1) { "حداقل یک پروژه باید باقی بماند." }
        require(items.any { it.id == id }) { "پروژه پیدا نشد." }
        check(directory(id).deleteRecursively()) { "حذف فایل‌های پروژه ناموفق بود." }
        val remaining = items.filterNot { it.id == id }
        write(remaining)
        if (activeProjectId() == id) check(prefs.edit().putString(KEY_ACTIVE, remaining.first().id).commit())
    }

    private fun write(items: List<ProjectRecord>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("updatedAt", it.updatedAt)) }
        check(prefs.edit().putString(KEY_PROJECTS, array.toString()).commit()) { "ذخیره فهرست پروژه‌ها ناموفق بود." }
    }

    private fun copyDirectory(source: File, destination: File): Boolean = runCatching {
        source.walkTopDown().forEach { file ->
            val relative = source.toPath().relativize(file.toPath()).toString()
            if (relative.split(File.separator).any { it in setOf(".git", ".gradle", "build", ".idea") }) return@forEach
            val target = File(destination, relative)
            if (file.isDirectory) check(target.exists() || target.mkdirs())
            else {
                target.parentFile?.let { check(it.exists() || it.mkdirs()) }
                file.copyTo(target, overwrite = true)
            }
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val KEY_PROJECTS = "projects_v1"
        const val KEY_ACTIVE = "active_project_id"
        const val DEFAULT_ID = "default-project"
    }
}
