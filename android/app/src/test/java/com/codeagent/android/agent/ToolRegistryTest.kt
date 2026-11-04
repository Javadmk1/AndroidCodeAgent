package com.codeagent.android.agent

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ToolRegistryTest {
    @Test
    fun buildRequiresApprovalAndRunsOnlyAfterApproval() = runBlocking {
        val workspaceDir = createTempDir(prefix = "agent-workspace-")
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
    fun unknownBuildTaskNeverRunsEvenWhenApproved() = runBlocking {
        val workspaceDir = createTempDir(prefix = "agent-workspace-")
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
