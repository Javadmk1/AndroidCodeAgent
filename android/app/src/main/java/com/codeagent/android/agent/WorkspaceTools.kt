package com.codeagent.android.agent

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
            ?.filterNot { it.name == ".codeagent-history" || it.name == ".ssh" }
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
        if (file.exists()) {
            require(file.isFile) { "Target is not a file" }
            saveUndoSnapshot(relativePath, file)
        }
        val temporary = File(parent, ".${file.name}.codeagent-tmp")
        temporary.writeText(content, StandardCharsets.UTF_8)
        if (file.exists() && !file.delete()) {
            temporary.delete()
            error("Cannot replace existing file")
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("Cannot finish saving file")
        }
    }

    /** Reads the last saved version for an approval preview, or null when no snapshot exists. */
    fun readUndoFile(relativePath: String): String? {
        resolve(relativePath)
        val snapshot = snapshotFile(relativePath)
        if (!snapshot.isFile) return null
        require(snapshot.length() <= MAX_FILE_BYTES) { "Undo snapshot exceeds 1 MB limit" }
        return snapshot.readText(StandardCharsets.UTF_8)
    }

    /** Returns whether a previous version is available for a one-step undo. */
    fun canUndo(relativePath: String): Boolean = snapshotFile(relativePath).isFile

    /** Restores the last saved version. The snapshot is consumed after a successful restore. */
    fun undoFile(relativePath: String): Boolean {
        val target = resolve(relativePath)
        val snapshot = snapshotFile(relativePath)
        if (!snapshot.isFile) return false
        val parent = target.parentFile ?: error("Invalid parent directory")
        if (!parent.exists() && !parent.mkdirs()) error("Cannot create parent directory")
        val temporary = File(parent, ".${target.name}.codeagent-undo")
        snapshot.copyTo(temporary, overwrite = true)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("Cannot replace current file")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("Cannot restore previous file")
        }
        snapshot.delete()
        return true
    }

    private fun saveUndoSnapshot(relativePath: String, source: File) {
        val history = File(root, ".codeagent-history")
        if (!history.exists() && !history.mkdirs()) error("Cannot create undo history")
        val key = MessageDigest.getInstance("SHA-256")
            .digest(relativePath.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val snapshot = File(history, "$key.bak")
        val temporary = File(history, "$key.tmp")
        source.copyTo(temporary, overwrite = true)
        if (snapshot.exists() && !snapshot.delete()) {
            temporary.delete()
            error("Cannot update undo history")
        }
        if (!temporary.renameTo(snapshot)) {
            temporary.delete()
            error("Cannot save undo history")
        }
    }

    private fun snapshotFile(relativePath: String): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(relativePath.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(root, ".codeagent-history"), "$key.bak")
    }

    fun searchInFiles(query: String, relativePath: String = ""): List<SearchMatch> {
        require(query.isNotBlank()) { "Search query is empty" }
        val start = resolve(relativePath)
        val excludedDirectories = setOf(".git", ".gradle", ".idea", ".kotlin", "build", ".codeagent-history")
        val files = if (start.isFile) listOf(start) else start.walkTopDown()
            .onEnter { directory ->
                directory == start || directory.name !in excludedDirectories
            }
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
        val normalized = relativePath.replace('\\\\', '/')
        require(normalized.split('/').none { it == ".codeagent-history" || it == ".ssh" }) {
            "دسترسی به تاریخچه داخلی و کلیدهای SSH از ابزارهای ایجنت مجاز نیست."
        }
        val candidate = if (relativePath.isBlank()) root else File(root, relativePath)
        val canonical = candidate.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "Path escapes workspace" }
        val privateHistory = File(root, ".codeagent-history").canonicalFile.toPath()
        require(!canonical.toPath().startsWith(privateHistory)) {
            "دسترسی به نسخه‌های پشتیبان داخلی از ابزارهای ایجنت مجاز نیست."
        }
        return canonical
    }

    data class SearchMatch(val path: String, val line: Int, val text: String)

    private companion object {
        const val MAX_FILE_BYTES = 1_048_576L
        const val MAX_SEARCH_FILES = 2_000
        const val MAX_MATCHES = 500
    }
}
