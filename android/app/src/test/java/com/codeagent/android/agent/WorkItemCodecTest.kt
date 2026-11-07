package com.codeagent.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkItemCodecTest {
    @Test fun roundTripsQueuedPromptsAndPlanSteps() {
        val items = listOf(
            WorkItem("12345678-1234-1234-1234-123456789012", "87654321-4321-4321-4321-210987654321",
                "Fix crash", "Investigate and fix startup crash", WorkKind.QUEUED_PROMPT, false, 100L),
            WorkItem("22345678-1234-1234-1234-123456789012", "87654321-4321-4321-4321-210987654321",
                "Add tests", "Cover the parser", WorkKind.PLAN_STEP, true, 200L)
        )
        assertEquals(items, WorkItemCodec.decode(WorkItemCodec.encode(items)))
    }

    @Test fun ignoresMalformedEntries() {
        val decoded = WorkItemCodec.decode("""[{"id":"bad","projectId":"bad","title":"x","kind":"NOPE"}]""")
        assertTrue(decoded.isEmpty())
    }
}
