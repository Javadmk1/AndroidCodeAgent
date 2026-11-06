package com.codeagent.android.agent

import org.json.JSONObject

/**
 * Local tool dispatcher. Read-only operations are immediate; writes require
 * an explicit approval callback supplied by the UI/controller.
 */
class ToolRegistry(
    private val workspace: WorkspaceTools,
    private val buildProject: suspend (String, suspend (String) -> Unit) -> JSONObject = { _, _ ->
        JSONObject().put("ok", false).put("error", "Build service is not configured")
    }
) {
    suspend fun execute(
        toolName: String,
        arguments: JSONObject,
        approveWrite: suspend (path: String, content: String) -> Boolean,
        approveBuild: suspend (task: String) -> Boolean = { false },
        onActivity: suspend (String) -> Unit = {}
    ): JSONObject {
        return when (toolName) {
            "list_files" -> {
                val path = arguments.optString("path", "")
                JSONObject().put("ok", true).put("files", org.json.JSONArray(workspace.listFiles(path)))
            }
            "read_file" -> {
                val path = arguments.getString("path")
                JSONObject().put("ok", true).put("path", path).put("content", workspace.readFile(path))
            }
            "search_in_files" -> {
                val query = arguments.getString("query")
                val path = arguments.optString("path", "")
                val matches = workspace.searchInFiles(query, path)
                val array = org.json.JSONArray()
                matches.forEach { match ->
                    array.put(JSONObject().put("path", match.path).put("line", match.line).put("text", match.text))
                }
                JSONObject().put("ok", true).put("matches", array)
            }
            "diff_file" -> {
                val path = arguments.getString("path")
                val proposed = arguments.getString("content")
                val current = runCatching { workspace.readFile(path) }.getOrDefault("")
                JSONObject().put("ok", true).put("path", path)
                    .put("diff", TextDiff.create(current, proposed))
                    .put("note", "Review this diff before requesting write_file approval.")
            }
            "build_project" -> {
                val task = arguments.optString("task", "assembleDebug")
                if (task !in ALLOWED_BUILD_TASKS) {
                    JSONObject().put("ok", false).put("error", "Gradle task is not in the approved allowlist")
                } else if (!approveBuild(task)) {
                    JSONObject().put("ok", false).put("error", "User denied build execution")
                } else {
                    buildProject(task, onActivity)
                }
            }
            "write_file" -> {
                val path = arguments.getString("path")
                if (!approveWrite(path, arguments.getString("content"))) {
                    JSONObject().put("ok", false).put("error", "User denied file write")
                } else {
                    workspace.writeFile(path, arguments.getString("content"))
                    JSONObject().put("ok", true).put("path", path)
                }
            }
            else -> JSONObject().put("ok", false).put("error", "Unknown or disabled tool: $toolName")
        }
    }

    fun toolNames(): List<String> = listOf("list_files", "read_file", "search_in_files", "diff_file", "write_file", "build_project")

    private companion object {
        val ALLOWED_BUILD_TASKS = setOf("tasks", "assembleDebug", "testDebugUnitTest", "lintDebug", "check", "clean")
    }
}
