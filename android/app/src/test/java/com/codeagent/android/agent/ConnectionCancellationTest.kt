package com.codeagent.android.agent

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ConnectionCancellationTest {
    @Test
    fun cancellationDisconnectsConnectionImmediately() {
        val connection = RecordingConnection()
        val job = Job()
        val handle = disconnectOnCancellation(connection, job)

        job.cancel()

        assertEquals(1, connection.disconnectCalls)
        handle.dispose()
    }

    @Test
    fun completedJobDoesNotDisconnectConnection() {
        val connection = RecordingConnection()
        val job = Job()
        val handle = disconnectOnCancellation(connection, job)

        job.complete()

        assertEquals(0, connection.disconnectCalls)
        handle.dispose()
    }

    @Test
    fun alreadyCancelledJobDisconnectsNewConnectionImmediately() {
        val connection = RecordingConnection()
        val job = Job()
        job.cancel()

        val handle = disconnectOnCancellation(connection, job)

        assertEquals(1, connection.disconnectCalls)
        handle.dispose()
    }

    private class RecordingConnection : HttpURLConnection(URL("http://localhost/")) {
        var disconnectCalls = 0

        override fun disconnect() {
            disconnectCalls += 1
        }

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}
