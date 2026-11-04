package com.codeagent.android.llm

import android.content.Context
import java.net.URI

data class ProviderConfig(
    val provider: String = "openai",
    val model: String = "gpt-4o-mini",
    val baseUrl: String = "https://api.openai.com/v1"
)

class ProviderConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    fun load(): ProviderConfig = ProviderConfig(
        provider = prefs.getString("provider", "openai") ?: "openai",
        model = prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini",
        baseUrl = prefs.getString("base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
    )

    fun save(config: ProviderConfig) {
        val provider = config.provider.trim()
        val model = config.model.trim()
        val endpoint = config.baseUrl.trim().trimEnd('/')
        require(PROVIDER_PATTERN.matches(provider)) { "Provider ID may contain only letters, numbers, _ and - (max 40)." }
        require(model.isNotBlank() && model.length <= 160) { "Enter a valid model name (max 160 characters)." }
        val uri = runCatching { URI(endpoint) }.getOrElse { throw IllegalArgumentException("API endpoint is not a valid URL.") }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Use a valid HTTPS API endpoint."
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "API endpoint cannot contain username, password, query parameters or fragments."
        }
        prefs.edit()
            .putString("provider", provider)
            .putString("model", model)
            .putString("base_url", endpoint)
            .apply()
    }

    private companion object {
        val PROVIDER_PATTERN = Regex("[a-zA-Z0-9_-]{1,40}")
    }
}
