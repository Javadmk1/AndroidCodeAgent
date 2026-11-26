package com.codeagent.android.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPermissionPolicyTest {
    private val policy = AgentPermissionPolicy()

    @Test fun permitsWorkspaceRelativePaths() {
        assertNull(policy.validate("read_file", JSONObject().put("path", "app/src/Main.kt")))
        assertNull(policy.validate("list_files", JSONObject().put("path", "app/src")))
        assertNull(policy.validate("list_files", JSONObject()))
    }

    @Test fun rejectsTraversalAbsoluteAndSensitivePaths() {
        listOf("../outside", "src/../../outside", "/etc/passwd", "C:/secrets.txt",
            ".env", "config/key.properties", "release/signing.jks").forEach { path ->
            val result = policy.validate("read_file", JSONObject().put("path", path))
            org.junit.Assert.assertNotNull("Expected rejection for $path", result)
        }
    }

    @Test fun rejectsControlCharactersAndOversizedPaths() {
        assertEquals("File path contains control characters",
            policy.validate("read_file", JSONObject().put("path", "src/Main.kt\nother")))
        assertEquals("File path is too long",
            policy.validate("read_file", JSONObject().put("path", "a".repeat(241))))
    }

    @Test fun allowsOnlyExplicitGradleTasks() {
        assertNull(policy.validate("build_project", JSONObject().put("task", "assembleDebug")))
        assertEquals("Gradle task is not in the approved allowlist",
            policy.validate("build_project", JSONObject().put("task", "publish")))
        assertEquals("Gradle task is not in the approved allowlist",
            policy.validate("build_project", JSONObject().put("task", "exec")))
    }

    @Test fun validatesPersistentTaskInputs() {
        assertNotNull(policy.validate("create_plan_step", JSONObject().put("title", " ")))
        assertNotNull(policy.validate("queue_request", JSONObject().put("prompt", "")))
        assertNull(policy.validate("queue_request", JSONObject().put("prompt", "Review module")))
    }

    @Test fun rejectsUnknownTools() {
        assertEquals("Unknown or disabled tool: run_shell",
            policy.validate("run_shell", JSONObject()))
    }

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }
}
