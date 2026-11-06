package com.codeagent.android.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GitHubRepository(
    val fullName: String,
    val defaultBranch: String,
    val private: Boolean,
    val htmlUrl: String
)

data class GitHubBranch(val name: String, val protected: Boolean)

data class GitHubPullRequest(
    val number: Int,
    val title: String,
    val state: String,
    val head: String,
    val base: String,
    val url: String
)

class GitHubApiClient {
    suspend fun repository(fullName: String, token: String? = null): GitHubRepository = withContext(Dispatchers.IO) {
        val json = request("GET", "/repos/" + safeRepo(fullName), token)
        GitHubRepository(json.getString("full_name"), json.getString("default_branch"),
            json.optBoolean("private"), json.getString("html_url"))
    }

    suspend fun branches(fullName: String, token: String? = null): List<GitHubBranch> = withContext(Dispatchers.IO) {
        val array = JSONArray(requestText("GET", "/repos/" + safeRepo(fullName) + "/branches?per_page=100", token))
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { GitHubBranch(it.optString("name"), it.optBoolean("protected")) }
        }
    }

    suspend fun pullRequests(fullName: String, token: String? = null): List<GitHubPullRequest> = withContext(Dispatchers.IO) {
        val array = JSONArray(requestText("GET", "/repos/" + safeRepo(fullName) + "/pulls?state=open&per_page=50", token))
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                val head = item.optJSONObject("head")?.optString("ref").orEmpty()
                val base = item.optJSONObject("base")?.optString("ref").orEmpty()
                GitHubPullRequest(item.optInt("number"), item.optString("title"), item.optString("state"),
                    head, base, item.optString("html_url"))
            }
        }
    }

    suspend fun createPullRequest(
        fullName: String,
        head: String,
        base: String,
        title: String,
        body: String,
        token: String
    ): GitHubPullRequest = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "برای ایجاد Pull Request توکن GitHub لازم است." }
        require(head.isNotBlank() && base.isNotBlank() && head != base) { "شاخه مبدأ و مقصد معتبر نیستند." }
        require(title.isNotBlank() && title.length <= 256) { "عنوان PR را وارد کن (حداکثر ۲۵۶ نویسه)." }
        val payload = JSONObject().put("head", head.trim()).put("base", base.trim())
            .put("title", title.trim()).put("body", body.take(20_000))
        val item = request("POST", "/repos/" + safeRepo(fullName) + "/pulls", token, payload)
        GitHubPullRequest(item.optInt("number"), item.optString("title"), item.optString("state"),
            item.optJSONObject("head")?.optString("ref").orEmpty(),
            item.optJSONObject("base")?.optString("ref").orEmpty(), item.optString("html_url"))
    }

    private fun safeRepo(value: String): String {
        val parts = value.trim().split("/")
        require(parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }) {
            "نام مخزن باید به شکل owner/repository باشد."
        }
        return parts.joinToString("/")
    }

    private fun request(method: String, path: String, token: String?, body: JSONObject? = null): JSONObject =
        JSONObject(requestText(method, path, token, body))

    private fun requestText(method: String, path: String, token: String?, body: JSONObject? = null): String {
        val connection = (URL("https://api.github.com" + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "AndroidCodeAgent")
            if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer " + token)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).optString("message") }.getOrDefault("")
                throw IllegalStateException("GitHub API $code: " + message.ifBlank { "درخواست ناموفق بود." }.take(300))
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
