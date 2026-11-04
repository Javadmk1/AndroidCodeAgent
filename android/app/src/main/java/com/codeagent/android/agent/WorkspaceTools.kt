package com.codeagent.android.agent

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Minimal local workspace toolset. All paths are relative to the app-owned workspace.
 * It intentionally does not expose arbitrary shell execution.
 */
class WorkspaceTools(workspaceDirectory: File) {
    private val root: File = workspaceDirectory.canonicalFile

    init {
        if (!root.exists() && !root.mkdirs()) error("Cannot create workspace directory")
        require(root.isDirectory) { "Workspace path is not a directory" }
    }

    fun listFiles(relativePath: String = ""): List<String> {
        val directory = resolve(relativePath)
        require(directory.isDirectory) { "Not a directory: $relativePath" }
        return directory.listFiles()
            ?.sortedBy { it.name.lowercase() }
            ?.map { file ->
                val child = if (relativePath.isBlank()) file.name else relativePath.trimEnd('/') + "/" + file.name
                if (file.isDirectory) "$child/" else child
            }.orEmpty()
    }

    fun readFile(relativePath: String): String {
        val file = resolve(relativePath)
        require(file.isFile) { "File not found: $relativePath" }
        require(file.length() <= MAX_FILE_BYTES) { "File exceeds 1 MB limit" }
        return file.readText(StandardCharsets.UTF_8)
    }

    fun writeFile(relativePath: String, content: String) {
        require(relativePath.isNotBlank()) { "File path is required" }
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_FILE_BYTES) { "Content exceeds 1 MB limit" }
        val file = resolve(relativePath)
        val parent = file.parentFile ?: error("Invalid parent directory")
        if (!parent.exists() && !parent.mkdirs()) error("Cannot create parent directory")
        if (file.exists()) require(file.isFile) { "Target is not a file" }
        file.writeText(content, StandardCharsets.UTF_8)
    }

    fun searchInFiles(query: String, relativePath: String = ""): List<SearchMatch> {
        require(query.isNotBlank()) { "Search query is empty" }
        val start = resolve(relativePath)
        val files = if (start.isFile) listOf(start) else start.walkTopDown()
            .filter { it.isFile && it.length() <= MAX_FILE_BYTES }
            .take(MAX_SEARCH_FILES)
            .toList()
        val results = mutableListOf<SearchMatch>()
        files.forEach { file ->
            val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrNull() ?: return@forEach
            lines.forEachIndexed { index, line ->
                if (line.contains(query, ignoreCase = true) && results.size < MAX_MATCHES) {
                    results += SearchMatch(root.toPath().relativize(file.toPath()).toString(), index + 1, line.take(500))
                }
            }
        }
        return results
    }

    private fun resolve(relativePath: String): File {
        val candidate = if (relativePath.isBlank()) root else File(root, relativePath)
        val canonical = candidate.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "Path escapes workspace" }
        return canonical
    }

    data class SearchMatch(val path: String, val line: Int, val text: String)

    private companion object {
        const val MAX_FILE_BYTES = 1_048_576L
        const val MAX_SEARCH_FILES = 2_000
        const val MAX_MATCHES = 500
    }
}
