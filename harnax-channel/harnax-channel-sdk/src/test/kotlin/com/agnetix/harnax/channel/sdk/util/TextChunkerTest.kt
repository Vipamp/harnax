package com.agnetix.harnax.channel.sdk.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [TextChunker] 的测试。所有带单条消息长度上限的平台都靠它把一段长回复拆成多次发送。比具体切点
 * 更重要的是两条性质：内容一个字都不能丢，每个分片都不能超过平台上限。
 */
class TextChunkerTest {

    private fun assertLossless(
        text: String,
        chunks: List<String>,
    ) {
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.none { it.isEmpty() }, "chunking produced an empty piece")
    }

    private fun maxUtf8Bytes(chunks: List<String>): Int = chunks.maxOf { it.toByteArray(Charsets.UTF_8).size }

    private fun assertNoLoneSurrogate(chunks: List<String>) {
        chunks.forEach { chunk ->
            var i = 0
            while (i < chunk.length) {
                val codePoint = Character.codePointAt(chunk, i)
                assertTrue(codePoint !in 0xD800..0xDFFF, "surrogate pair split across chunks")
                i += Character.charCount(codePoint)
            }
        }
    }

    @Test
    fun `text that fits is returned unchanged`() {
        assertEquals(listOf("short answer"), TextChunker.splitByChars("short answer", 4000))
    }

    @Test
    fun `empty text yields a single empty piece`() {
        assertEquals(listOf(""), TextChunker.splitByChars("", 10))
    }

    @Test
    fun `a newline is preferred as the cut point`() {
        val chunks = TextChunker.splitByChars("aaa\nbbb", 4)

        assertEquals(listOf("aaa\n", "bbb"), chunks)
    }

    @Test
    fun `a space is preferred over cutting a word in half`() {
        val chunks = TextChunker.splitByChars("hello world", 6)

        assertEquals(listOf("hello ", "world"), chunks)
    }

    @Test
    fun `a word longer than the cap is hard cut`() {
        val chunks = TextChunker.splitByChars("abcdefgh", 3)

        assertEquals(listOf("abc", "def", "gh"), chunks)
    }

    @Test
    fun `a leading over-long word is cut before the following break is used`() {
        val chunks = TextChunker.splitByChars("xxxxxxxxxx y", 5)

        assertEquals(listOf("xxxxx", "xxxxx", " y"), chunks)
    }

    @Test
    fun `char limits count utf-16 units not bytes`() {
        val text = "渠道消息分片测试".repeat(3)

        val chunks = TextChunker.splitByChars(text, 10)

        assertEquals(3, chunks.size)
        assertEquals(10, chunks[0].length)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `byte limits keep multi-byte text inside the cap`() {
        val text = "渠道消息分片测试".repeat(5)

        val chunks = TextChunker.splitByUtf8Bytes(text, 30)

        assertTrue(chunks.size > 1)
        assertTrue(maxUtf8Bytes(chunks) <= 30, "a chunk exceeded the byte cap")
        assertLossless(text, chunks)
    }

    @Test
    fun `a paragraph is split without losing characters`() {
        val text = (1..40).joinToString("\n") { "line $it ${"x".repeat(20)}" }

        val chunks = TextChunker.splitByChars(text, 60)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 60 }, "a chunk exceeded the char cap")
        assertLossless(text, chunks)
    }

    @Test
    fun `an astral character stays whole even when it does not fit`() {
        val text = "\uD83D\uDC4Da"

        val chunks = TextChunker.splitByUtf8Bytes(text, 3)

        assertEquals(listOf("\uD83D\uDC4D", "a"), chunks)
        assertNoLoneSurrogate(chunks)
    }

    @Test
    fun `emoji inside a long answer are never torn apart`() {
        val text = ("review \uD83D\uDC4D ok. ".repeat(10)).trim()

        val chunks = TextChunker.splitByUtf8Bytes(text, 24)

        assertTrue(chunks.size > 1)
        assertTrue(maxUtf8Bytes(chunks) <= 24, "a chunk exceeded the byte cap")
        assertNoLoneSurrogate(chunks)
        assertLossless(text, chunks)
    }

    @Test
    fun `limit must be positive`() {
        assertThrows<IllegalArgumentException> { TextChunker.splitByChars("text", 0) }
        assertThrows<IllegalArgumentException> { TextChunker.splitByUtf8Bytes("text", -1) }
    }
}
