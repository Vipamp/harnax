package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.entity.ToolInvocationLog
import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationEvent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.kotlin.argumentCaptor
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The writer's own promises: never block the caller, never throw at the caller, never send an empty
 * statement, and never let a long payload break the batch it travelled in.
 *
 * Rows are asserted field by field rather than by count, because a batch that writes the wrong tenant onto
 * a row passes a count-shaped test and then shows somebody else's calls on their own page.
 */
class ToolInvocationAdaptorImplTest {

    private val mapper = mock(ToolInvocationLogMapper::class.java)

    private fun adaptor(
        queueCapacity: Int = 512,
        batchSize: Int = 64,
        capturePayload: Boolean = true,
        captureMaxChars: Int = 2000,
    ) = ToolInvocationAdaptorImpl(
        toolInvocationLogMapper = mapper,
        queueCapacity = queueCapacity,
        batchSize = batchSize,
        flushIntervalMs = 200L,
        capturePayload = capturePayload,
        captureMaxChars = captureMaxChars,
    )

    private fun event(
        kind: String = ToolInvocationLog.KIND_BUILTIN,
        toolName: String = "send_email",
        argsJson: String? = "{\"to\":\"a@b.c\"}",
        resultText: String? = "sent",
        errorMessage: String? = null,
    ) = ToolInvocationEvent(
        tenantId = 5L,
        agentId = 7L,
        sessionId = "web-1",
        userId = 9L,
        kind = kind,
        toolName = toolName,
        outcome = if (errorMessage == null) ToolInvocationLog.OUTCOME_SUCCESS else ToolInvocationLog.OUTCOME_ERROR,
        argsJson = argsJson,
        resultText = resultText,
        errorMessage = errorMessage,
        startEpochMilli = 1_700_000_000_000L,
        endEpochMilli = 1_700_000_000_250L,
    )

    private fun capturedRows(): List<ToolInvocationLog> {
        // mockito-kotlin's captor rather than `ArgumentCaptor.forClass(List::class.java)`: the raw one either
        // will not compile against `batchInsert`'s non-null parameter or, once cast, null-checks `capture()`,
        // which a Mockito matcher always returns as null.
        val captor = argumentCaptor<List<ToolInvocationLog>>()
        verify(mapper).batchInsert(captor.capture())
        return captor.firstValue
    }

    /** A column back to the event's own millis, so an assertion on `ts` checks the instant and not a copy of it. */
    private fun millisOf(time: LocalDateTime?): Long = requireNotNull(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Nested
    @DisplayName("row shape")
    inner class RowShape {
        @Test
        fun `an event becomes one row carrying its attribution and measured duration`() {
            val writer = adaptor()
            writer.emit(event())

            assertEquals(1, writer.drainAndFlush())
            val row = capturedRows().single()
            assertEquals(5L, row.tenantId)
            assertEquals(7L, row.agentId)
            assertEquals("web-1", row.sessionId)
            assertEquals(9L, row.userId)
            assertEquals(ToolInvocationLog.KIND_BUILTIN, row.kind)
            assertEquals("send_email", row.toolName)
            assertEquals(ToolInvocationLog.OUTCOME_SUCCESS, row.outcome)
            assertEquals(250L, row.durationMs)
            assertEquals("{\"to\":\"a@b.c\"}", row.argsJson)
            assertEquals("sent", row.resultExcerpt)
            // ts is what the indexes and the rollup key on: the call's own end instant at millisecond
            // precision, not the writer's clock and not a truncated second.
            assertEquals(1_700_000_000_250L, millisOf(row.ts))
            assertEquals(millisOf(row.ts), millisOf(row.endTime))
            assertEquals(1_700_000_000_000L, millisOf(row.startTime))
        }

        @Test
        fun `a negative duration from a clock that went backwards is filed as zero`() {
            val writer = adaptor()
            writer.emit(ToolInvocationEvent(5L, 7L, "web-1", 9L, ToolInvocationLog.KIND_BUILTIN, "now", outcome = ToolInvocationLog.OUTCOME_SUCCESS, argsJson = null, resultText = null, errorMessage = null, startEpochMilli = 200L, endEpochMilli = 100L))

            writer.drainAndFlush()

            assertEquals(0L, capturedRows().single().durationMs)
        }
    }

    @Nested
    @DisplayName("payload switches")
    inner class Payload {
        @Test
        fun `capture-payload off leaves both body columns null`() {
            val writer = adaptor(capturePayload = false)
            writer.emit(event())

            assertEquals(1, writer.drainAndFlush())
            val row = capturedRows().single()
            assertNull(row.argsJson)
            assertNull(row.resultExcerpt)
            // The measurement is not a payload: what is counted has to survive turning bodies off.
            assertEquals(250L, row.durationMs)
        }

        @Test
        fun `a body over the limit is cut with a marker instead of vanishing`() {
            val writer = adaptor(captureMaxChars = 10)
            writer.emit(event(argsJson = "x".repeat(50), resultText = "y".repeat(50)))

            writer.drainAndFlush()
            val row = capturedRows().single()

            assertTrue(row.argsJson!!.startsWith("xxxxxxxxxx"))
            assertTrue(row.argsJson!!.endsWith("(truncated)"))
            assertEquals(10 + "…(truncated)".length, row.argsJson!!.length)
            assertTrue(row.resultExcerpt!!.endsWith("(truncated)"))
        }

        @Test
        fun `a failure reason is cut to the column width whatever the payload limit says`() {
            val writer = adaptor(captureMaxChars = 2000)
            writer.emit(event(errorMessage = "boom ".repeat(400)))

            writer.drainAndFlush()

            // error_message is varchar(512): letting the payload limit decide it would fail the whole insert.
            assertTrue(capturedRows().single().errorMessage!!.length <= 512)
        }
    }

    @Nested
    @DisplayName("batching and overflow")
    inner class Batching {
        @Test
        fun `an empty queue sends no statement at all`() {
            val writer = adaptor()

            assertEquals(0, writer.drainAndFlush())

            // foreach over an empty list is invalid SQL; the guard is here rather than in the XML.
            verifyNoInteractions(mapper)
        }

        @Test
        fun `a batch of N rows goes out in one statement`() {
            val writer = adaptor(batchSize = 8)
            repeat(5) { writer.emit(event(toolName = "tool-$it")) }

            assertEquals(5, writer.drainAndFlush())

            val rows = capturedRows()
            assertEquals(5, rows.size)
            assertEquals(listOf("tool-0", "tool-1", "tool-2", "tool-3", "tool-4"), rows.map { it.toolName })
        }

        @Test
        fun `a drain stops at the batch size and leaves the rest queued`() {
            val writer = adaptor(batchSize = 2)
            repeat(5) { writer.emit(event()) }

            assertEquals(2, writer.drainAndFlush())
            assertEquals(2, writer.drainAndFlush())
            assertEquals(1, writer.drainAndFlush())
            assertEquals(0, writer.drainAndFlush())
        }

        @Test
        fun `a full queue drops the newest event and counts it instead of blocking`() {
            val writer = adaptor(queueCapacity = 1)
            writer.emit(event(toolName = "first"))

            assertDoesNotThrow {
                writer.emit(event(toolName = "second"))
                writer.emit(event(toolName = "third"))
            }

            assertTrue(writer.droppedCount >= 2)
            assertEquals(1, writer.drainAndFlush())
            assertEquals("first", capturedRows().single().toolName)
        }

        @Test
        fun `what was queued before shutdown is still written`() {
            val writer = adaptor()
            writer.emit(event())
            writer.emit(event())

            writer.shutdown()

            // The worker is not running in a unit test, so this asserts the drain in shutdown() itself.
            assertEquals(2, capturedRows().size)
        }

        @Test
        fun `a mapper that throws is not carried up to the caller`() {
            val failing = mock(ToolInvocationLogMapper::class.java)
            `when`(failing.batchInsert(anyList())).thenThrow(IllegalStateException("db down"))
            val writer =
                ToolInvocationAdaptorImpl(
                    toolInvocationLogMapper = failing,
                    queueCapacity = 512,
                    batchSize = 64,
                    flushIntervalMs = 200L,
                    capturePayload = true,
                    captureMaxChars = 2000,
                )
            writer.emit(event())

            assertDoesNotThrow { writer.drainAndFlush() }
        }
    }
}
