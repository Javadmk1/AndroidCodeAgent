package com.codeagent.android.llm

/** Normalizes provider base URLs without duplicating endpoint suffixes. */
object ApiEndpoint {
    fun chat(config: ProviderConfig): String {
        val base = config.baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://", ignoreCase = true)) { "نشانی API باید HTTPS باشد." }
        val path = runCatching { java.net.URI(base).path.orEmpty().trimEnd('/') }
            .getOrElse { throw IllegalArgumentException("نشانی API معتبر نیست.") }
        val anthropic = config.provider.equals("anthropic", ignoreCase = true)
        if (anthropic) {
            if (path.endsWith("/v1/messages") || path.endsWith("/messages")) return base
            if (path.endsWith("/v1")) return base + "/messages"
            return base + "/v1/messages"
        }
        if (path.endsWith("/chat/completions")) return base
        if (path.endsWith("/v1")) return base + "/chat/completions"
        return base + "/v1/chat/completions"
    }
}
