package com.codeagent.android.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRequestBodyBuilderTest {
    private val config = ProviderConfig("openai", "vision-model", "https://api.example.com/v1")
    private val messages = listOf(
        ChatMessage("user", "این تصویر را بررسی کن.\n[[IMAGE:image/png:YWJjMTIz]]")
    )

    @Test fun openAiUsesNativeImageUrlContentBlock() {
        val message = LlmRequestBodyBuilder.openAiBody(config, messages)
            .getJSONArray("messages").getJSONObject(0)
        val content = message.getJSONArray("content")

        assertEquals("user", message.getString("role"))
        assertEquals(2, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("این تصویر را بررسی کن.", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals("data:image/png;base64,YWJjMTIz",
            content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test fun anthropicUsesBase64ImageSourceAndKeepsSystemSeparate() {
        val body = LlmRequestBodyBuilder.anthropicBody(
            ProviderConfig("anthropic", "vision-model", "https://api.anthropic.com/v1"),
            listOf(ChatMessage("system", "system rules")) + messages
        )
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val image = content.getJSONObject(1)

        assertEquals("system rules", body.getString("system"))
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image", image.getString("type"))
        assertEquals("base64", image.getJSONObject("source").getString("type"))
        assertEquals("image/png", image.getJSONObject("source").getString("media_type"))
        assertEquals("YWJjMTIz", image.getJSONObject("source").getString("data"))
    }

    @Test fun textOnlyMessagesKeepTheirOriginalStringShape() {
        val body = LlmRequestBodyBuilder.openAiBody(config, listOf(ChatMessage("user", "hello")))
        val content = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals("hello", content)
    }

    @Test fun imageMarkerIsNotLeakedIntoTextBlock() {
        val body = LlmRequestBodyBuilder.openAiBody(config, messages)
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertFalse(content.getJSONObject(0).getString("text").contains("[[IMAGE:"))
    }

    @Test fun rejectsUnsupportedImageMimeTypes() {
        val invalid = listOf(ChatMessage("user", "look [[IMAGE:image/heic:YWJj]]"))
        val error = runCatching { LlmRequestBodyBuilder.openAiBody(config, invalid) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("قالب تصویر پشتیبانی نمی‌شود"))
    }

    @Test fun rejectsIncompleteImageMarkers() {
        val invalid = listOf(ChatMessage("user", "look [[IMAGE:image/png:YWJj"))
        val error = runCatching { LlmRequestBodyBuilder.openAiBody(config, invalid) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("ناقص"))
    }
}
