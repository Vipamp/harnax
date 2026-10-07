package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.entity.ToolInvocationLog
import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationEvent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
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
        flushIntervalMs: Long = 200L,
        capturePayload: Boolean = true,
        captureMaxChars: Int = 2000,
    ) = ToolInvocationAdaptorImpl(
        toolInvocationLogMapper = mapper,
        queueCapacity = queueCapacity,
        batchSize = batchSize,
        flushIntervalMs = flushIntervalMs,
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
            writer.emit(event(errorMessage = "boom"))

            assertEquals(1, writer.drainAndFlush())
            val row = capturedRows().single()
            assertNull(row.argsJson)
            assertNull(row.resultExcerpt)
            // The measurement is not a payload: what is counted has to survive turning bodies off.
            assertEquals(250L, row.durationMs)
            // Neither is a failure reason: the switch exists for argument and receipt leakage, and a failed
            // call's own reason is what the page has left to show once bodies are off.
            assertEquals("boom", row.errorMessage)
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
        fun `a cut that lands inside a surrogate pair drops the half character`() {
            val writer = adaptor(captureMaxChars = 15)
            // One emoji is two UTF-16 units, so ten of them are 20 units and a 15-unit cut falls between the
            // two halves. The marker hides it from `last()`, so the unit under test is the one before it.
            writer.emit(event(argsJson = "{\"note\":\"验证通过\"}", resultText = "😀".repeat(10)))

            writer.drainAndFlush()
            val row = capturedRows().single()

            // A lone surrogate is what makes utf8mb4 reject the row, and one rejected row loses the whole batch.
            val body = row.resultExcerpt!!.removeSuffix("…(truncated)")
            assertFalse(Character.isHighSurrogate(body.last()))
            assertEquals("😀".repeat(7), body)
            // Non-ASCII payload below its own limit is written as it came, marker-free and uncut.
            assertEquals("{\"note\":\"验证通过\"}", row.argsJson)
        }

        @Test
        fun `a tool name over the column width is clamped without a marker`() {
            val writer = adaptor()
            // A name this long can only come from outside: an MCP server's own tools/list, or a model that
            // invented one. It is varchar(255), so one such row would fail the statement carrying 63 others.
            writer.emit(event(kind = ToolInvocationLog.KIND_MCP, toolName = "x".repeat(300)))

            assertEquals(1, writer.drainAndFlush())
            val row = capturedRows().single()

            assertEquals(255, row.toolName.length)
            // The marker would be worse than the overflow it prevents: this column is part of the aggregate's
            // unique key, so a marked name opens a bucket no registry can name.
            assertFalse(row.toolName.contains("…"))
        }

        @Test
        fun `a clamp that lands inside an astral character drops the half character`() {
            val writer = adaptor()
            // One astral character is two UTF-16 units straddling index 254, so the clamp stops between its two
            // halves and leaves a lone high surrogate — the one value that makes the column reject the row, and
            // a rejected row is the whole batch of counters gone rather than this name shortened.
            writer.emit(event(kind = ToolInvocationLog.KIND_MCP, toolName = "x".repeat(254) + "\uD83D\uDC00" + "y".repeat(45)))

            assertEquals(1, writer.drainAndFlush())
            val row = capturedRows().single()

            // Asserted on the row's own value, not on how many statements went out.
            assertEquals(254, row.toolName.length)
            assertFalse(Character.isHighSurrogate(row.toolName.last()))
        }

        @Test
        fun `a failure reason is cut to the column width whatever the payload limit says`() {
            val writer = adaptor(captureMaxChars = 2000)
            writer.emit(event(errorMessage = "boom ".repeat(400)))

            writer.drainAndFlush()

            // error_message is varchar(512): letting the payload limit decide it would fail the whole insert.
            assertTrue(capturedRows().single().errorMessage!!.length <= 512)
        }

        @Test
        fun `a negative capture limit is clamped so the row it would have lost is still written`() {
            // `text.length <= -1` is never true, so `take(-1)` throws inside `toRow()` and the drain's catch
            // swallows it: the batch is discarded while the caller is still told how many events it held. The
            // falsifier is therefore the statement the mapper never saw, not the return count.
            val writer = adaptor(captureMaxChars = -1)
            writer.emit(event(argsJson = "{\"to\":\"a@b.c\"}", resultText = "sent"))

            writer.drainAndFlush()

            val row = capturedRows().single()
            // Clamped to the band's floor of 1 character, and the marker still rides along: the body is
            // shortened, not silently dropped, so a reader can tell a limit was hit.
            assertEquals("{…(truncated)", row.argsJson)
            assertEquals("s…(truncated)", row.resultExcerpt)
            // The measurement never depended on the limit and must not lose the row either.
            assertEquals(250L, row.durationMs)
            assertEquals("send_email", row.toolName)
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
        fun `a queue capacity of zero is clamped to one event that still gets queued`() {
            // `ArrayBlockingQueue` rejects a capacity below 1 with its own IAE, so an env that lost a digit
            // would stop agent-service from starting over a counter. Clamped, the queue is one deep, and that
            // is asserted on what the queue does: the first event is accepted and reaches the mapper, the next
            // lands on the counted-drop path instead of on an exception or a silent block.
            val writer = adaptor(queueCapacity = 0)
            writer.emit(event(toolName = "first"))
            writer.emit(event(toolName = "second"))

            assertEquals(1, writer.drainAndFlush())
            assertEquals(1L, writer.droppedCount)
            assertEquals("first", capturedRows().single().toolName)
        }

        @Test
        fun `a negative batch size is clamped to a drain that still takes a row`() {
            // `ArrayList(-1)` throws, and that allocation sits outside `pump`'s try block: the worker dies on
            // its first pass and every event then queues up behind a dead thread until the capacity drops them.
            // Clamped to the band's floor of 1, progress is what the drain reports: one row per pass, both
            // events written, and a third pass that finds the queue empty rather than throwing.
            val writer = adaptor(batchSize = -1)
            writer.emit(event(toolName = "first"))
            writer.emit(event(toolName = "second"))

            assertEquals(1, writer.drainAndFlush())
            assertEquals(1, writer.drainAndFlush())
            assertEquals(0, writer.drainAndFlush())

            // Two statements of one row each: the floor is a batch size of 1, and a drain that silently took
            // nothing would leave this mock untouched while still returning a plausible number.
            verify(mapper, times(2)).batchInsert(anyList())
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
        fun `a shutdown drains every batch instead of only the first one`() {
            val writer = adaptor(batchSize = 2)
            repeat(5) { writer.emit(event(toolName = "tool-$it")) }

            writer.shutdown()

            // 2+2+1: with batchSize left at its default the queue empties in one drain and the loop's own
            // iteration is never observed, so a shutdown that stopped after one batch would look correct here.
            val captor = argumentCaptor<List<ToolInvocationLog>>()
            verify(mapper, times(3)).batchInsert(captor.capture())
            assertEquals(listOf(2, 2, 1), captor.allValues.map { it.size })
            assertEquals(5, captor.allValues.sumOf { it.size })
        }

        @Test
        fun `an event emitted after shutdown is counted rather than lost silently`() {
            val writer = adaptor()

            writer.shutdown()
            writer.emit(event())

            // Nothing drains after `shutdown()` has done its own, so an accepted event is queued forever: it
            // belongs on the counted-drop path, because "the page is empty" owes somebody a number.
            // No waiting here — the drain promise is settled by `shutdown()` returning, and this asserts that
            // the post-shutdown event never became a statement.
            assertEquals(1L, writer.droppedCount)
            verifyNoInteractions(mapper)
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

        @Test
        fun `the writer's terminal flush reports its loss instead of escaping as an exception`() {
            // The batch here has already left the queue: `pump()` breaks out of its loop on an interrupt holding
            // whatever the last drain gave it, so `shutdown()`'s drain can no longer recover these rows and this
            // flush is the last place their loss can be stated. Unguarded it threw out of the loop, which is both
            // silent for the page and fatal for the thread.
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
            val partial = listOf(event(toolName = "a"), event(toolName = "b"), event(toolName = "c"))

            assertEquals(0L, writer.droppedCount)
            // The explicit type argument selects `assertDoesNotThrow`'s supplier overload: left to inference,
            // Kotlin picks the `Executable` one and the returned count arrives as Unit.
            val held = assertDoesNotThrow<Int> { writer.flushBatch(partial) }

            // Counted by exactly the rows it held, so the figure matches the statement that never landed.
            assertEquals(3L, writer.droppedCount)
            // Still the number of events it took, whatever the drain's caller does with it: `shutdown()` loops on
            // it, and a zero here would stop that loop with the queue still full.
            assertEquals(3, held)
        }
    }

    /**
     * The commit decision `pump()` reaches on every pass, pinned without starting a thread: no test can reach
     * the worker loop, so the decision is what moved out to here rather than the loop being raced.
     */
    @Nested
    @DisplayName("flush decision")
    inner class FlushDecision {
        @Test
        fun `a full batch commits without waiting for the timeout`() {
            val writer = adaptor(batchSize = 4)

            // The throughput case: a full batch must not sit and wait out its interval.
            assertTrue(writer.shouldFlush(size = 4, timedOut = false))
        }

        @Test
        fun `a partial batch commits once the poll has timed out`() {
            val writer = adaptor(batchSize = 4)

            // Three calls and a quiet session must reach the page on the next tick, not on the next call.
            assertTrue(writer.shouldFlush(size = 3, timedOut = true))
        }

        @Test
        fun `a partial batch stays queued inside the timeout`() {
            val writer = adaptor(batchSize = 4)

            // Neither commit reason holds: not full, and the poll has not given up on the next call yet.
            assertFalse(writer.shouldFlush(size = 3, timedOut = false))
        }

        @Test
        fun `an empty batch never commits whatever the timeout says`() {
            val writer = adaptor(batchSize = 4)

            assertFalse(writer.shouldFlush(size = 0, timedOut = true))
            assertFalse(writer.shouldFlush(size = 0, timedOut = false))
        }

        @Test
        fun `a flush interval of zero is clamped so an idle pass blocks instead of spinning`() {
            // A timeout of 0 makes `queue.poll` return at once, so the pass finds an empty batch, commits
            // nothing and loops again: a daemon thread burning a core for the life of the process, invisible
            // because nothing is ever written wrong. The number worth pinning is the one handed to the poll.
            val writer = adaptor(flushIntervalMs = 0)

            // 10 ms is the band's floor, asserted as a literal so widening the band has to be a decision.
            assertEquals(10L, writer.idleWaitMillis)

            val startedNanos = System.nanoTime()
            assertNull(writer.awaitFirstEvent())
            val waitedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
            // One-sided on purpose: a pass that waits its full interval can only read slower, never faster, so
            // half the floor is a bound no loaded machine can miss, while an instant return cannot hide.
            assertTrue(waitedMillis >= 5L, "idle pass returned after ${waitedMillis}ms — it spun instead of blocking")

            // And the spin is not throughput: with nothing queued, no commit reason holds.
            assertFalse(writer.shouldFlush(size = 0, timedOut = true))
        }
    }
}
