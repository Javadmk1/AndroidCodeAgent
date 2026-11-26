package com.codeagent.android.agent

import org.json.JSONObject

/**
 * Central preflight policy for every model-invoked tool.
 *
 * This policy is deliberately separate from UI approval: a request must pass
 * validation here AND receive the relevant explicit user approval before a
 * mutating operation is executed by ToolRegistry.
 */
internal class AgentPermissionPolicy {
    fun validate(toolName: String, arguments: JSONObject): String? {
        if (toolName !in KNOWN_TOOLS) return "Unknown or disabled tool: $toolName"

        when (toolName) {
            "list_files" -> validateOptionalPath(arguments.optString("path", ""))?.let { return it }
            "search_in_files" -> {
                val query = arguments.optString("query", "")
                if (query.isBlank()) return "Search query is required"
                if (query.length > MAX_SEARCH_QUERY) return "Search query is too long"
                validateOptionalPath(arguments.optString("path", ""))?.let { return it }
            }
            "read_file", "diff_file", "undo_file", "write_file" -> {
                val path = arguments.optString("path", "")
                if (path.isBlank()) return "File path is required"
                validateOptionalPath(path)?.let { return it }
            }
            "build_project" -> {
                val task = arguments.optString("task", "assembleDebug")
                if (task !in ALLOWED_BUILD_TASKS) return "Gradle task is not in the approved allowlist"
            }
            "write_file", "diff_file" -> {
                val content = arguments.optString("content", "")
                if (content.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                    return "File content exceeds the 1 MB limit"
                }
            }
            "create_plan_step" -> {
                val title = arguments.optString("title", "").trim()
                val details = arguments.optString("details", "")
                if (title.isEmpty()) return "Plan step title is required"
                if (title.length > MAX_PLAN_TITLE) return "Plan step title is too long"
                if (details.length > MAX_PLAN_DETAILS) return "Plan step details are too long"
            }
            "queue_request" -> {
                val prompt = arguments.optString("prompt", "")
                if (prompt.isBlank()) return "Queued request cannot be empty"
                if (prompt.length > MAX_QUEUED_PROMPT) return "Queued request is too long"
            }
        }
        return null
    }

    private fun validateOptionalPath(rawPath: String): String? {
        if (rawPath.isBlank()) return null
        if (rawPath.length > MAX_PATH_LENGTH) return "File path is too long"
        if (rawPath.any { it.code < 32 || it.code == 127 }) return "File path contains control characters"
        val normalized = rawPath.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.startsWith("//") ||
            Regex("^[A-Za-z]:").containsMatchIn(normalized)
        ) return "Absolute paths are not allowed"
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return "Path traversal or empty path segments are not allowed"
        }
        if (ProjectExportRules.isSensitivePath(normalized)) {
            return "Access to sensitive files and directories is blocked"
        }
        return null
    }

    private companion object {
        const val MAX_PATH_LENGTH = 240
        const val MAX_SEARCH_QUERY = 500
        const val MAX_FILE_BYTES = 1_048_576
        const val MAX_PLAN_TITLE = 160
        const val MAX_PLAN_DETAILS = 2_000
        const val MAX_QUEUED_PROMPT = 8_000
        val KNOWN_TOOLS = setOf(
            "list_files", "read_file", "search_in_files", "diff_file", "undo_file",
            "write_file", "create_plan_step", "queue_request", "build_project"
        )
        val ALLOWED_BUILD_TASKS = setOf(
            "tasks", "assembleDebug", "testDebugUnitTest", "lintDebug", "check", "clean"
        )
    }
}
