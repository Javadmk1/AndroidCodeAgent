package com.codeagent.android.agent

internal data class ParsedImageAttachment(
    val mimeType: String,
    val base64Data: String
)

internal data class ParsedMultimodalContent(
    val text: String,
    val images: List<ParsedImageAttachment>
)

/** Validates the app's private image marker before it reaches a provider request. */
internal object MultimodalContentParser {
    private const val MARKER = "[[IMAGE:"
    private const val MAX_IMAGES = 4
    private const val MAX_BASE64_CHARS = 6 * 1024 * 1024
    private val allowedMimeTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    private val base64Pattern = Regex("[A-Za-z0-9+/]+={0,2}")

    fun parse(raw: String): ParsedMultimodalContent {
        val images = mutableListOf<ParsedImageAttachment>()
        val text = StringBuilder()
        var cursor = 0

        while (true) {
            val start = raw.indexOf(MARKER, cursor)
            if (start < 0) {
                text.append(raw.substring(cursor))
                break
            }

            text.append(raw.substring(cursor, start))
            val end = raw.indexOf("]]", start + MARKER.length)
            require(end >= 0) { "داده پیوست تصویر ناقص است." }
            require(images.size < MAX_IMAGES) { "حداکثر $MAX_IMAGES تصویر در هر پیام مجاز است." }

            val payload = raw.substring(start + MARKER.length, end)
            val separator = payload.indexOf(':')
            require(separator > 0) { "قالب پیوست تصویر معتبر نیست." }

            val mime = payload.substring(0, separator).lowercase()
            val data = payload.substring(separator + 1)
            require(mime in allowedMimeTypes) { "قالب تصویر پشتیبانی نمی‌شود: $mime" }
            require(data.length <= MAX_BASE64_CHARS && data.length % 4 == 0 &&
                base64Pattern.matches(data)) {
                "داده Base64 تصویر نامعتبر است یا از حد مجاز بیشتر است."
            }

            images += ParsedImageAttachment(mime, data)
            cursor = end + 2
        }

        return ParsedMultimodalContent(text.toString().trim(), images)
    }
}
