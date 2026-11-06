package com.codeagent.android.agent

/**
 * Produces a bounded, line-oriented unified-style diff for review before an approved write.
 * This deliberately avoids loading unbounded files or allocating an unbounded LCS matrix.
 */
object TextDiff {
    private const val MAX_LINES = 400
    private const val MAX_CELLS = 120_000
    private const val CONTEXT = 3

    fun create(oldText: String, newText: String): String {
        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')
        require(oldLines.size <= MAX_LINES && newLines.size <= MAX_LINES) {
            "Diff is limited to $MAX_LINES lines per version"
        }
        require(oldLines.size.toLong() * newLines.size.toLong() <= MAX_CELLS) {
            "Diff is too large to calculate safely"
        }
        if (oldText == newText) return "No changes."
        val rows = oldLines.size + 1
        val cols = newLines.size + 1
        val lcs = Array(rows) { IntArray(cols) }
        for (i in oldLines.indices.reversed()) {
            for (j in newLines.indices.reversed()) {
                lcs[i][j] = if (oldLines[i] == newLines[j]) 1 + lcs[i + 1][j + 1]
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val edits = mutableListOf<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < oldLines.size || j < newLines.size) {
            when {
                i < oldLines.size && j < newLines.size && oldLines[i] == newLines[j] -> {
                    edits += ' ' to oldLines[i]; i++; j++
                }
                j < newLines.size && (i == oldLines.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                    edits += '+' to newLines[j]; j++
                }
                else -> { edits += '-' to oldLines[i]; i++ }
            }
        }
        val changed = edits.indices.filter { edits[it].first != ' ' }
        val keep = BooleanArray(edits.size)
        changed.forEach { index ->
            for (k in maxOf(0, index - CONTEXT)..minOf(edits.lastIndex, index + CONTEXT)) keep[k] = true
        }
        val output = StringBuilder("--- current\n+++ proposed\n")
        var index = 0
        while (index < edits.size) {
            if (!keep[index]) { index++; continue }
            output.append("@@\n")
            while (index < edits.size && keep[index]) {
                output.append(edits[index].first).append(edits[index].second).append('\n')
                index++
            }
            while (index < edits.size && !keep[index]) index++
        }
        return output.toString().take(24_000)
    }
}
