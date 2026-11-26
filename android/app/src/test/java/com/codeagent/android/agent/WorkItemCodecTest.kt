package com.codeagent.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkItemCodecTest {
    private val projectA = "87654321-4321-4321-4321-210987654321"
    private val projectB = "97654321-4321-4321-4321-210987654321"

    @Test fun roundTripsQueuedPromptsAndPlanSteps() {
        val items = listOf(
            WorkItem("12345678-1234-1234-1234-123456789012", projectA,
                "Fix crash", "Investigate and fix startup crash", WorkKind.QUEUED_PROMPT, false, 100L),
            WorkItem("22345678-1234-1234-1234-123456789012", projectA,
                "Add tests", "Cover the parser", WorkKind.PLAN_STEP, true, 200L)
        )
        assertEquals(items, WorkItemCodec.decode(WorkItemCodec.encode(items)))
    }

    @Test fun ignoresMalformedEntries() {
        val decoded = WorkItemCodec.decode("""[{"id":"bad","projectId":"bad","title":"x","kind":"NOPE"}]""")
        assertTrue(decoded.isEmpty())
    }

    @Test fun deletingProjectRemovesItsQueuedPromptsAndPlanStepsOnly() {
        val items = listOf(
            WorkItem("12345678-1234-1234-1234-123456789012", projectA,
                "Queued prompt", "do work", WorkKind.QUEUED_PROMPT, false, 100L),
            WorkItem("22345678-1234-1234-1234-123456789012", projectA,
                "Plan step", "details", WorkKind.PLAN_STEP, false, 200L),
            WorkItem("32345678-1234-1234-1234-123456789012", projectB,
                "Keep this", "other project", WorkKind.QUEUED_PROMPT, false, 300L)
        )
        val remaining = WorkItemCodec.withoutProject(items, projectA)
        assertEquals(listOf(items.last()), remaining)
    }

    @Test fun startupReconciliationDropsOrphanedProjectTasks() {
        val items = listOf(
            WorkItem("12345678-1234-1234-1234-123456789012", projectA,
                "Orphan", "deleted project", WorkKind.QUEUED_PROMPT, false, 100L),
            WorkItem("22345678-1234-1234-1234-123456789012", projectB,
                "Live", "existing project", WorkKind.PLAN_STEP, false, 200L)
        )
        assertEquals(listOf(items.last()), WorkItemCodec.forProjects(items, setOf(projectB)))
    }
}
