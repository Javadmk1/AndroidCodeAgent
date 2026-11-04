package com.codeagent.android.agent

import android.content.Context
import android.net.Uri
import java.io.File

class CloudBuildClient(private val context: Context) {
    fun validateEndpoint(endpoint: String): String {
        require(endpoint.startsWith("https://")) { "HTTPS endpoint required" }
        return endpoint.trimEnd('/')
    }
}
