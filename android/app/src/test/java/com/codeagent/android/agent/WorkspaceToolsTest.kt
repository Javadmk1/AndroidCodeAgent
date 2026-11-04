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
