package com.codeagent.android.agent

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectImportRulesTest {
    @Test
    fun acceptsNormalFileAndDirectoryNames() {
        ProjectImportRules.requireSafeName("MainActivity.kt")
        ProjectImportRules.requireSafeName("src")
        ProjectImportRules.requireSafeName("پروژه من")
    }

    @Test
    fun rejectsPathComponentsAndSeparators() {
        listOf("", ".", "..", "../outside", "folder/file", "folder\\\\file").forEach { name ->
            try {
                ProjectImportRules.requireSafeName(name)
                fail("Expected unsafe name to be rejected: $name")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun acceptsOnlyBoundedDirectoryDepth() {
        ProjectImportRules.requireDepth(0)
        ProjectImportRules.requireDepth(64)
        try {
            ProjectImportRules.requireDepth(65)
            fail("Expected excessive depth to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("عمق"))
        }
    }
}
