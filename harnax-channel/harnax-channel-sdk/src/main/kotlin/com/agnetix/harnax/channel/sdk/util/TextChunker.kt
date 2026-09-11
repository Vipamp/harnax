package com.agnetix.harnax.channel.sdk.util

/**
 * Text splitting helpers used when a platform caps the size of a single message.
 *
 * Splits prefer a newline, then a space, and only fall back to a hard cut, so a
 * chunked answer stays readable. The whole message is walked once (O(n)) and no
 * surrogate pair is ever split across chunks.
 */
object TextChunker {

    /** Split into pieces of at most [maxChars] UTF-16 chars (DingTalk, WeCom limits are char-based). */
    fun splitByChars(
        text: String,
        maxChars: Int,
    ): List<String> = split(text, maxChars, measureBytes = false)

    /** Split into pieces of at most [maxBytes] UTF-8 bytes (Feishu limits are byte-based). */
    fun splitByUtf8Bytes(
        text: String,
        maxBytes: Int,
    ): List<String> = split(text, maxBytes, measureBytes = true)

    private fun split(
        text: String,
        limit: Int,
        measureBytes: Boolean,
    ): List<String> {
        require(limit > 0) { "Chunk limit must be positive, got $limit" }
        val sizeOf = costFunction(measureBytes)
        if (sizeOf(text, 0, text.length) <= limit) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0
        var acc = 0
        var lastBreak = -1
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val charCount = Character.charCount(cp)
            val cost = if (measureBytes) utf8Cost(cp) else charCount
            if (acc + cost > limit && i > start) {
                val cut = if (lastBreak > start) lastBreak else i
                chunks.add(text.substring(start, cut))
                start = cut
                i = cut
                acc = 0
                lastBreak = -1
                continue
            }
            if (cp == NEWLINE || cp == SPACE) lastBreak = i + charCount
            acc += cost
            i += charCount
        }
        if (start < text.length) chunks.add(text.substring(start))
        return chunks.filter { it.isNotEmpty() }
    }

    private fun costFunction(measureBytes: Boolean): (String, Int, Int) -> Int = if (measureBytes) { s, from, to -> utf8CostRange(s, from, to) } else { s, from, to -> to - from }

    private fun utf8CostRange(
        s: String,
        from: Int,
        to: Int,
    ): Int {
        var bytes = 0
        var i = from
        while (i < to) {
            val cp = Character.codePointAt(s, i)
            bytes += utf8Cost(cp)
            i += Character.charCount(cp)
        }
        return bytes
    }

    private fun utf8Cost(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    private const val NEWLINE = '\n'.code
    private const val SPACE = ' '.code
}
