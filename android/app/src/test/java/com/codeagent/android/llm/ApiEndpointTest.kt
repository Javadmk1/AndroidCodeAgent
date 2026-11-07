package com.codeagent.android.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiEndpointTest {
    @Test fun openAiRootAddsVersionAndChatPath() {
        assertEquals("https://api.openai.com/v1/chat/completions", ApiEndpoint.chat(ProviderConfig("openai", "model", "https://api.openai.com")))
    }
    @Test fun openAiV1DoesNotDuplicateVersion() {
        assertEquals("https://api.deepseek.com/v1/chat/completions", ApiEndpoint.chat(ProviderConfig("compatible", "model", "https://api.deepseek.com/v1/")))
    }
    @Test fun fullOpenAiEndpointIsPreserved() {
        assertEquals("https://proxy.example/v1/chat/completions", ApiEndpoint.chat(ProviderConfig("compatible", "model", "https://proxy.example/v1/chat/completions")))
    }
    @Test fun anthropicRootUsesVersionedMessagesPath() {
        assertEquals("https://api.anthropic.com/v1/messages", ApiEndpoint.chat(ProviderConfig("anthropic", "model", "https://api.anthropic.com")))
    }
    @Test fun anthropicV1AddsMessagesOnly() {
        assertEquals("https://api.anthropic.com/v1/messages", ApiEndpoint.chat(ProviderConfig("anthropic", "model", "https://api.anthropic.com/v1")))
    }
}
