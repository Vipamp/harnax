package com.agnetix.harnax.admin.util

import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Reads the JSON envelope one store object carries.
 *
 * The bytes in the bucket are not the markdown text. `MinioBaseStore.write()` serialises a `StoreWrapper`
 * around every file, so an object holds
 * `{"key":"/MEMORY.md","value":{"content":"…","encoding":…,"created_at":…,"modified_at":…},"version":3}`,
 * and the text a human has to see lives at `value.content`. Reading the object body straight would show
 * the caller a JSON document instead of their memory.
 *
 * A body that is not a wrapper — an object some other writer put under the same prefix, or a truncated
 * upload — answers with empty text rather than an exception: the caller's own memory is unaffected, and a
 * read endpoint has no business failing on a neighbour's key. The odd shape is logged so it stays visible.
 */
object MemoryRecordParser {

    private val log = LoggerFactory.getLogger(MemoryRecordParser::class.java)

    /**
     * The envelope of one object.
     *
     * @param content the file text at `value.content`, empty when the object carries none
     * @param modifiedAt the `value.modified_at` field as text, null when the writer left it out — the
     *   object's own storage timestamp is the fallback and belongs to the caller, not to this record
     */
    data class Record(
        val content: String,
        val modifiedAt: String?,
    )

    /** Parses a whole object body, empty-handed rather than throwing when it is not a wrapper. */
    fun parse(
        body: String,
        objectMapper: ObjectMapper,
    ): Record {
        val value = try {
            objectMapper.readTree(body)?.path("value")
        } catch (e: Exception) {
            log.warn("[memory] Object body is not a store wrapper, treating it as empty: {}", e.message)
            null
        }
        if (value == null || value.isNull) return Record("", null)
        return Record(contentOf(value), textOf(value.path("modified_at")))
    }

    /**
     * The file text.
     *
     * A list is joined with newlines because a file body has been written both as one string and as lines;
     * an object or an absent field is not a text file and reads as empty.
     */
    private fun contentOf(value: JsonNode): String {
        val content = value.path("content")
        return when {
            content.isArray -> content.joinToString("\n") { textOf(it).orEmpty() }
            content.isObject -> {
                log.warn("[memory] `value.content` is an object, which is not a text file body — showing it empty")
                ""
            }

            else -> textOf(content).orEmpty()
        }
    }

    private fun textOf(node: JsonNode?): String? = when {
        node == null || node.isNull -> null
        node.isString || node.isNumber || node.isBoolean -> node.asText()
        else -> null
    }
}
