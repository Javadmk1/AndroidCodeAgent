package com.codeagent.android.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import java.net.HttpURLConnection

/**
 * Disconnects a blocking HTTP connection as soon as its owning coroutine is cancelled.
 * The early-cancellation callback is needed because a blocked read may otherwise wait for
 * the socket read timeout before the coroutine can reach its finally block.
 */
@OptIn(InternalCoroutinesApi::class)
internal fun disconnectOnCancellation(
    connection: HttpURLConnection,
    job: Job
): DisposableHandle = job.invokeOnCompletion(
    onCancelling = true,
    invokeImmediately = true
) { cause ->
    if (cause is CancellationException) {
        connection.disconnect()
    }
}
