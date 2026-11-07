package com.codeagent.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesReadsListsAndSearchesInsideWorkspace() {
        val workspace = WorkspaceTools(temporaryFolder.newFolder("workspace"))
        workspace.writeFile("src/Main.kt", "fun main() = println(\"hello agent\")")

        assertEquals("fun main() = println(\"hello agent\")", workspace.readFile("src/Main.kt"))
        assertTrue(workspace.listFiles("src").contains("src/Main.kt"))
        assertEquals("src/Main.kt", workspace.searchInFiles("hello").single().path)
    }


    @Test
    fun keepsOnePreviousVersionAndRestoresItOnce() {
        val workspace = WorkspaceTools(temporaryFolder.newFolder("undo-workspace"))
        workspace.writeFile("src/Main.kt", "version one")
        workspace.writeFile("src/Main.kt", "version two")

        assertTrue(workspace.canUndo("src/Main.kt"))
        assertTrue(workspace.undoFile("src/Main.kt"))
        assertEquals("version one", workspace.readFile("src/Main.kt"))
        assertTrue(!workspace.canUndo("src/Main.kt"))
        assertTrue(!workspace.undoFile("src/Main.kt"))
    }

    @Test
    fun hidesAndBlocksPrivateAgentHistoryAndSshDirectories() {
        val root = temporaryFolder.newFolder("private-history-workspace")
        val workspace = WorkspaceTools(root)
        val history = java.io.File(root, ".codeagent-history").apply { mkdirs() }
        java.io.File(history, "backup.kt").writeText("private old source")
        val ssh = java.io.File(root, ".ssh").apply { mkdirs() }
        java.io.File(ssh, "id_ed25519").writeText("private key")

        assertTrue(workspace.listFiles().none { it.contains(".codeagent-history") || it.contains(".ssh") })
        listOf(".codeagent-history/backup.kt", ".ssh/id_ed25519").forEach { path ->
            try {
                workspace.readFile(path)
                fail("Expected private path to be blocked: " + path)
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("مجاز نیست"))
            }
        }
    }

    @Test
    fun textDiffShowsOnlyChangedLinesWithContext() {
        val diff = TextDiff.create("alpha\nbeta\ngamma", "alpha\nBETA\ngamma")
        assertTrue(diff.contains("-beta"))
        assertTrue(diff.contains("+BETA"))
        assertTrue(diff.contains(" alpha"))
    }

    @Test
    fun textDiffReportsIdenticalContent() {
        assertEquals("No changes.", TextDiff.create("same", "same"))
    }

    @Test
    fun textDiffRejectsOversizedInput() {
        try {
            TextDiff.create("x\n".repeat(401), "y")
            fail("Expected oversized diff to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("400 lines"))
        }
    }


    @Test
    fun rejectsSymlinkThatPointsOutsideWorkspace() {
        val workspaceDir = temporaryFolder.newFolder("symlink-workspace")
        val outside = temporaryFolder.newFile("outside.txt").apply { writeText("private") }
        val link = java.io.File(workspaceDir, "escape.txt")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        val workspace = WorkspaceTools(workspaceDir)

        try {
            workspace.readFile("escape.txt")
            fail("Expected symlink escape to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Path escapes"))
        }

        try {
            workspace.writeFile("escape.txt", "overwritten")
            fail("Expected symlink write to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Path escapes"))
        }
        assertEquals("private", outside.readText())
    }

    @Test
    fun rejectsParentTraversal() {
        val workspace = WorkspaceTools(temporaryFolder.newFolder("workspace"))
        try {
            workspace.readFile("../outside.txt")
            fail("Expected path traversal to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Path escapes"))
        }
    }

    @Test
    fun rejectsOversizedWrites() {
        val workspace = WorkspaceTools(temporaryFolder.newFolder("workspace"))
        try {
            workspace.writeFile("large.txt", "x".repeat(1_048_577))
            fail("Expected oversized content to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("1 MB"))
        }
    }
    @Test
    fun rejectsSymlinkEscape() {
        val workspaceDir = temporaryFolder.newFolder("workspace")
        val outside = temporaryFolder.newFolder("outside")
        java.io.File(outside, "secret.txt").writeText("private")
        java.nio.file.Files.createSymbolicLink(
            java.io.File(workspaceDir, "linked").toPath(),
            outside.toPath()
        )
        val workspace = WorkspaceTools(workspaceDir)
        try {
            workspace.readFile("linked/secret.txt")
            fail("Expected symlink traversal to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Path escapes"))
        }
    }

}
