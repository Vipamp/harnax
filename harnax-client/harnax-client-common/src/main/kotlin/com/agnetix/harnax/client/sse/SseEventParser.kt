package com.agnetix.harnax.client.sse

import com.agnetix.harnax.client.dto.ChatEvent
import org.slf4j.LoggerFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/**
 * SSE (Server-Sent Events) event parser.
 *
 * Parses raw SSE text lines ("data: {...}") into [ChatEvent] objects
 * using Jackson polymorphic deserialization.
 *
 * Usage:
 * ```
 * val parser = SseEventParser()
 * val event = parser.parseLine("data: {\"eventType\":\"TextEvent\",\"message\":\"hello\"}")
 * ```
 */
class SseEventParser {

    private val log = LoggerFactory.getLogger(SseEventParser::class.java)

    private val objectMapper: ObjectMapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /**
     * Parse a single SSE data line into a [ChatEvent].
     *
     * @param line Raw SSE line (e.g. "data: {...}")
     * @return Parsed ChatEvent, or null if the line is not a valid data line
     */
    fun parseLine(line: String): ChatEvent? {
        if (!line.startsWith("data:")) return null
        val json = line.removePrefix("data:").trim()
        if (json.isEmpty() || json == "[DONE]") return null

        return try {
            objectMapper.readValue(json, ChatEvent::class.java)
        } catch (e: Exception) {
            log.warn("[SseEventParser] Failed to parse SSE event: ${json.take(200)}, error: ${e.message}")
            null
        }
    }

    /**
     * Parse a raw JSON string into a [ChatEvent].
     */
    fun parseJson(json: String): ChatEvent? {
        if (json.isBlank()) return null
        return try {
            objectMapper.readValue(json, ChatEvent::class.java)
        } catch (e: Exception) {
            log.warn("[SseEventParser] Failed to parse JSON event: ${json.take(200)}, error: ${e.message}")
            null
        }
    }

    /**
     * Get the shared ObjectMapper instance for external use.
     */
    fun getObjectMapper(): ObjectMapper = objectMapper
}
