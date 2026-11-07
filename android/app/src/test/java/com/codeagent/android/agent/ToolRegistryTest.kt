package com.codeagent.android.agent

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ToolRegistryTest {
    private fun newWorkspaceDir(): File =
        File(System.getProperty("java.io.tmpdir"), "agent-workspace-${System.nanoTime()}").apply {
            check(mkdirs()) { "Could not create workspace temp directory: $absolutePath" }
        }

    @Test
    fun buildRequiresApprovalAndRunsOnlyAfterApproval() = runBlocking {
        val workspaceDir = newWorkspaceDir()
        try {
            var executedTask: String? = null
            val registry = ToolRegistry(
                WorkspaceTools(workspaceDir),
                buildProject = { task, _ ->
                    executedTask = task
                    JSONObject().put("ok", true).put("task", task)
                }
            )

            val denied = registry.execute(
                "build_project",
                JSONObject().put("task", "assembleDebug"),
                approveWrite = { _, _ -> false },
                approveBuild = { false }
            )
            assertFalse(denied.getBoolean("ok"))
            assertEquals(null, executedTask)

            val approved = registry.execute(
                "build_project",
                JSONObject().put("task", "assembleDebug"),
                approveWrite = { _, _ -> false },
                approveBuild = { it == "assembleDebug" }
            )
            assertTrue(approved.getBoolean("ok"))
            assertEquals("assembleDebug", executedTask)
        } finally {
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun toolReturnsStructuredErrorForInvalidWorkspacePath() = runBlocking {
        val workspaceDir = newWorkspaceDir()
        try {
            val registry = ToolRegistry(WorkspaceTools(workspaceDir))
            val result = registry.execute(
                "read_file",
                JSONObject().put("path", "../outside.txt"),
                approveWrite = { _, _ -> false }
            )
            assertFalse(result.getBoolean("ok"))
            assertTrue(result.optString("error").isNotBlank())
            assertEquals("read_file", result.optString("tool"))
        } finally {
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun undoRequiresApprovalAndRestoresPreviousVersion() = runBlocking {
        val workspaceDir = newWorkspaceDir()
        try {
            val workspace = WorkspaceTools(workspaceDir)
            workspace.writeFile("src/Main.kt", "old version")
            workspace.writeFile("src/Main.kt", "new version")
            val registry = ToolRegistry(workspace)

            val denied = registry.execute(
                "undo_file",
                JSONObject().put("path", "src/Main.kt"),
                approveWrite = { path, proposed ->
                    assertEquals("src/Main.kt", path)
                    assertEquals("old version", proposed)
                    false
                }
            )
            assertFalse(denied.getBoolean("ok"))
            assertEquals("new version", workspace.readFile("src/Main.kt"))

            val approved = registry.execute(
                "undo_file",
                JSONObject().put("path", "src/Main.kt"),
                approveWrite = { path, proposed ->
                    path == "src/Main.kt" && proposed == "old version"
                }
            )
            assertTrue(approved.getBoolean("ok"))
            assertEquals("old version", workspace.readFile("src/Main.kt"))
            assertFalse(workspace.canUndo("src/Main.kt"))
        } finally {
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun agentCanCreatePlanStepsAndQueueExplicitFollowUpRequests() = runBlocking {
        val workspaceDir = newWorkspaceDir()
        try {
            val plan = mutableListOf<Pair<String, String>>()
            val queued = mutableListOf<String>()
            val registry = ToolRegistry(
                WorkspaceTools(workspaceDir),
                createPlanStep = { title, details ->
                    plan += title to details
                    JSONObject().put("ok", true).put("title", title)
                },
                queuePrompt = { prompt ->
                    queued += prompt
                    JSONObject().put("ok", true)
                }
            )
            val planResult = registry.execute(
                "create_plan_step",
                JSONObject().put("title", "Add regression test").put("details", "Verify retry behavior"),
                approveWrite = { _, _ -> false }
            )
            val queueResult = registry.execute(
                "queue_request",
                JSONObject().put("prompt", "Review the next module"),
                approveWrite = { _, _ -> false }
            )
            assertTrue(planResult.getBoolean("ok"))
            assertTrue(queueResult.getBoolean("ok"))
            assertEquals(listOf("Add regression test" to "Verify retry behavior"), plan)
            assertEquals(listOf("Review the next module"), queued)
        } finally {
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun unknownBuildTaskNeverRunsEvenWhenApproved() = runBlocking {
        val workspaceDir = newWorkspaceDir()
        try {
            var executed = false
            val registry = ToolRegistry(
                WorkspaceTools(workspaceDir),
                buildProject = { _, _ ->
                    executed = true
                    JSONObject().put("ok", true)
                }
            )
            val result = registry.execute(
                "build_project",
                JSONObject().put("task", "publish"),
                approveWrite = { _, _ -> false },
                approveBuild = { true }
            )
            assertFalse(result.getBoolean("ok"))
            assertFalse(executed)
        } finally {
            workspaceDir.deleteRecursively()
        }
    }
}
