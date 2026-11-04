package com.codeagent.android.llm

import android.content.Context

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
        require(config.model.isNotBlank()) { "Model is required" }
        require(config.baseUrl.startsWith("https://")) { "Use an HTTPS API endpoint" }
        prefs.edit()
            .putString("provider", config.provider)
            .putString("model", config.model.trim())
            .putString("base_url", config.baseUrl.trim().trimEnd('/'))
            .apply()
    }
}
