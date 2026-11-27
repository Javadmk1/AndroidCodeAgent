package com.codeagent.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalContentParserTest {
    @Test fun parsesImageAndKeepsPromptText() {
        val parsed = MultimodalContentParser.parse("این تصویر را بررسی کن.\n[[IMAGE:image/png:YWJjMTIz]]")

        assertEquals("این تصویر را بررسی کن.", parsed.text)
        assertEquals(1, parsed.images.size)
        assertEquals("image/png", parsed.images.single().mimeType)
        assertEquals("YWJjMTIz", parsed.images.single().base64Data)
    }

    @Test fun supportsImageOnlyMessages() {
        val parsed = MultimodalContentParser.parse("[[IMAGE:image/jpeg:YWJj]]")

        assertEquals("", parsed.text)
        assertEquals(1, parsed.images.size)
    }

    @Test fun preservesPlainTextMessages() {
        val parsed = MultimodalContentParser.parse("سلام، یک برنامه بساز")

        assertEquals("سلام، یک برنامه بساز", parsed.text)
        assertTrue(parsed.images.isEmpty())
    }

    @Test fun parsesMultipleImagesInOneMessage() {
        val parsed = MultimodalContentParser.parse(
            "مقایسه کن [[IMAGE:image/png:YWJj]] و [[IMAGE:image/webp:ZGVm]]"
        )

        assertEquals("مقایسه کن  و", parsed.text)
        assertEquals(2, parsed.images.size)
    }

    @Test fun rejectsIncompleteImageMarker() {
        val error = runCatching {
            MultimodalContentParser.parse("تصویر [[IMAGE:image/png:YWJj")
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("ناقص"))
    }

    @Test fun rejectsUnsupportedImageTypes() {
        val error = runCatching {
            MultimodalContentParser.parse("[[IMAGE:image/heic:YWJj]]")
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("پشتیبانی نمی‌شود"))
    }

    @Test fun rejectsInvalidBase64() {
        val error = runCatching {
            MultimodalContentParser.parse("[[IMAGE:image/png:abc$]]")
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("Base64"))
    }

    @Test fun rejectsMoreThanFourImages() {
        val marker = "[[IMAGE:image/png:YWJj]]"
        val error = runCatching {
            MultimodalContentParser.parse(List(5) { marker }.joinToString(""))
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("حداکثر 4"))
    }
}
