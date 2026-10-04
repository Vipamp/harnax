package com.agnetix.harnax.admin.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * The JSON envelope `MinioBaseStore.write()` puts around every memory file.
 *
 * The object body is not the markdown: it is
 * `{"key":"/MEMORY.md","value":{"content":"…","encoding":…,"created_at":…,"modified_at":…},"version":3}`.
 * What the owner has to see is `value.content`, so these assertions pin the field path down — reading the
 * body raw, or `key`, or the whole envelope, is the bug this class exists to avoid.
 */
@DisplayName("MemoryRecordParser - the store wrapper around a memory file")
class MemoryRecordParserTest {

    private val mapper = ObjectMapper()

    /** The shape `MinioBaseStore` serialises today, field for field. */
    @Test
    fun `the curated text comes back from value content`() {
        val body = """
            {"key":"/MEMORY.md","value":{"content":"- the user likes terse answers",
            "encoding":"utf-8","created_at":"2026-10-01T09:00:00Z","modified_at":"2026-10-05T12:00:00Z"},
            "version":3}
        """.trimIndent().replace("\n", "")

        val record = MemoryRecordParser.parse(body, mapper)

        assertEquals("- the user likes terse answers", record.content)
        assertEquals("2026-10-05T12:00:00Z", record.modifiedAt)
    }

    @Test
    fun `a ledger day is read the same way as the curated layer`() {
        val body = """{"key":"/2026-10-05.md","value":{"content":"- asked about the release date"},"version":1}"""

        assertEquals("- asked about the release date", MemoryRecordParser.parse(body, mapper).content)
    }

    /** Markdown carries newlines, quotes and backslashes; the wrapper escapes them and the text must come back whole. */
    @Test
    fun `an escaped multi-line body is returned as the text it holds`() {
        val body = """{"key":"/MEMORY.md","value":{"content":"# head\nsecond \"quoted\" \\ line"},"version":2}"""

        assertEquals("# head\nsecond \"quoted\" \\ line", MemoryRecordParser.parse(body, mapper).content)
    }

    @Test
    fun `a body written as lines is joined back into text`() {
        val body = """{"key":"/MEMORY.md","value":{"content":["- one","- two"]},"version":4}"""

        assertEquals("- one\n- two", MemoryRecordParser.parse(body, mapper).content)
    }

    @Test
    fun `an empty file is empty text and not an error`() {
        val body = """{"key":"/MEMORY.md","value":{"content":""},"version":1}"""

        assertEquals("", MemoryRecordParser.parse(body, mapper).content)
    }

    /**
     * A key under the owner's prefix that is not a wrapper belongs to something else that writes the bucket.
     * Showing it empty keeps a neighbour's oddity from failing the owner's own page.
     */
    @Test
    fun `a body that is not a wrapper reads as empty`() {
        assertEquals("", MemoryRecordParser.parse("not json at all", mapper).content)
        assertEquals("", MemoryRecordParser.parse("""{"something":"else"}""", mapper).content)
        assertEquals("", MemoryRecordParser.parse("", mapper).content)
        assertNull(MemoryRecordParser.parse("not json at all", mapper).modifiedAt)
    }

    /** A numeric timestamp has to survive as text rather than be dropped for not being a string. */
    @Test
    fun `a numeric version and timestamp are still read as text`() {
        val body = """{"key":"/MEMORY.md","value":{"content":"- x","modified_at":1759636800},"version":3}"""

        val record = MemoryRecordParser.parse(body, mapper)
        assertEquals("- x", record.content)
        assertEquals("1759636800", record.modifiedAt)
    }
}
