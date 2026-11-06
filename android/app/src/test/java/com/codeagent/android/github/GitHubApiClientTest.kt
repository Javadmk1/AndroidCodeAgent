package com.codeagent.android.github

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApiClientTest {
    @Test fun rejectsRepositoryPathTraversalBeforeNetworkRequest() = runBlocking {
        val error = runCatching { GitHubApiClient().repository("../other") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun rejectsMalformedRepositoryNamesBeforeNetworkRequest() = runBlocking {
        val error = runCatching { GitHubApiClient().repository("owner/repo?query=value") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
