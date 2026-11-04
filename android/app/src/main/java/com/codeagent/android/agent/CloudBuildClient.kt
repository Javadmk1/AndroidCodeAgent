package com.codeagent.android.agent

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
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

    fun validateTask(task: String): String {
        require(task in ALLOWED_TASKS) { "این فرمان در فهرست امن Gradle نیست." }
        return task
    }

    suspend fun getEnvironment(endpoint: String, token: String): String = withContext(Dispatchers.IO) {
        val connection = URL(validateEndpoint(endpoint) + "/v1/environment").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer " + token)
            connection.connectTimeout = 15000
            connection.readTimeout = 100000
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("SDK environment error $code: \${body.take(500)}")
            JSONObject(body).optString("output")
        } finally { connection.disconnect() }
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

    suspend fun submitZip(endpoint: String, token: String, zipUri: Uri, projectDir: String, gradleTask: String = "assembleDebug"): String =
        withContext(Dispatchers.IO) {
            val task = validateTask(gradleTask)
            val boundary = "----ACA" + UUID.randomUUID().toString().replace("-", "")
            val connection = (URL(validateEndpoint(endpoint) + "/v1/builds").openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setChunkedStreamingMode(0)
                connection.connectTimeout = 30000
                connection.readTimeout = 120000
                connection.setRequestProperty("Authorization", "Bearer " + token)
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
                connection.outputStream.use { output ->
                    fun write(value: String) { output.write(value.toByteArray(Charsets.UTF_8)) }
                    write("--$boundary\r\nContent-Disposition: form-data; name=\"project_dir\"\r\n\r\n$projectDir\r\n")
                    write("--$boundary\r\nContent-Disposition: form-data; name=\"gradle_task\"\r\n\r\n$task\r\n")
                    write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"project.zip\"\r\nContent-Type: application/zip\r\n\r\n")
                    context.contentResolver.openInputStream(zipUri)?.use { input ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    } ?: error("Could not read ZIP file")
                    write("\r\n--$boundary--\r\n")
                }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("Build service error $code: ${body.take(800)}")
                JSONObject(body).getString("build_id")
            } finally {
                connection.disconnect()
            }
        }


    private companion object { val ALLOWED_TASKS = setOf("tasks", "assembleDebug", "testDebugUnitTest", "lintDebug", "check", "clean") }

    suspend fun downloadArtifact(endpoint: String, token: String, buildId: String): File =
        withContext(Dispatchers.IO) {
            val connection = (URL(validateEndpoint(endpoint) + "/v1/builds/" + buildId + "/artifact").openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer " + token)
                connection.connectTimeout = 15000
                connection.readTimeout = 120000
                val code = connection.responseCode
                if (code !in 200..299) {
                    val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    error("Artifact download failed $code: ${body.take(500)}")
                }
                val file = File(context.cacheDir, "cloud-build-" + buildId + ".zip")
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                file
            } finally {
                connection.disconnect()
            }
        }

}
