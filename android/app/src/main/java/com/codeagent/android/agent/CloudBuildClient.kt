package com.codeagent.android.agent

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CloudBuildStatus(val status: String, val log: String, val error: String?, val artifactReady: Boolean)

class CloudBuildClient(private val context: Context) {
    fun validateEndpoint(endpoint: String): String {
        require(endpoint.startsWith("https://")) { "HTTPS endpoint required" }
        return endpoint.trimEnd('/')
    }

    suspend fun getStatus(endpoint: String, token: String, buildId: String): CloudBuildStatus =
        withContext(Dispatchers.IO) {
            val connection = (URL(validateEndpoint(endpoint) + "/v1/builds/" + buildId).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer " + token)
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("Build service error $code: ${body.take(500)}")
                val json = JSONObject(body)
                CloudBuildStatus(json.optString("status"), json.optString("log"),
                    json.optString("error").takeIf { it.isNotBlank() && it != "null" },
                    json.optBoolean("artifact_ready"))
            } finally {
                connection.disconnect()
            }
        }
}
