package com.codeagent.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionCodecTest {
    private val projectA = "87654321-4321-4321-4321-210987654321"
    private val projectB = "97654321-4321-4321-4321-210987654321"

    @Test fun roundTripsExecutionAndApprovalStates() {
        val records = listOf(
            TaskExecutionRecord(
                "12345678-1234-1234-1234-123456789012",
                projectA,
                "Implement a feature",
                TaskExecutionStatus.RUNNING,
                100L,
                110L
            ),
            TaskExecutionRecord(
                "22345678-1234-1234-1234-123456789012",
                projectA,
                "Review a change",
                TaskExecutionStatus.WAITING_APPROVAL,
                200L,
                210L
            )
        )

        assertEquals(records, TaskExecutionCodec.decode(TaskExecutionCodec.encode(records)))
    }

    @Test fun runningAndWaitingApprovalBecomeInterruptedAfterRestart() {
        val records = listOf(
            record("12345678-1234-1234-1234-123456789012", projectA, TaskExecutionStatus.RUNNING),
            record("22345678-1234-1234-1234-123456789012", projectA, TaskExecutionStatus.WAITING_APPROVAL),
            record("32345678-1234-1234-1234-123456789012", projectA, TaskExecutionStatus.SUCCEEDED)
        )

        val recovered = TaskExecutionCodec.recoverInterrupted(records, 500L)

        assertEquals(TaskExecutionStatus.INTERRUPTED, recovered[0].status)
        assertEquals(TaskExecutionStatus.INTERRUPTED, recovered[1].status)
        assertEquals(500L, recovered[0].updatedAt)
        assertTrue(recovered[0].error.isNotBlank())
        assertEquals(TaskExecutionStatus.SUCCEEDED, recovered[2].status)
    }

    @Test fun projectCleanupKeepsOtherProjectsOnly() {
        val records = listOf(
            record("12345678-1234-1234-1234-123456789012", projectA, TaskExecutionStatus.FAILED),
            record("22345678-1234-1234-1234-123456789012", projectB, TaskExecutionStatus.SUCCEEDED)
        )

        assertEquals(listOf(records.last()), TaskExecutionCodec.withoutProject(records, projectA))
        assertEquals(listOf(records.last()), TaskExecutionCodec.forProjects(records, setOf(projectB)))
    }

    @Test fun malformedExecutionRecordsAreIgnored() {
        assertTrue(TaskExecutionCodec.decode(
            """[{"id":"bad","projectId":"bad","prompt":"x","status":"UNKNOWN"}]"""
        ).isEmpty())
    }

    private fun record(id: String, projectId: String, status: TaskExecutionStatus) =
        TaskExecutionRecord(id, projectId, "A valid task", status, 100L, 200L)
}
