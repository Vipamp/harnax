package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.entity.ToolInvocationLog
import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationAdaptor
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationEvent
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes tool invocation events to `tool_invocation_log` on a thread that is not the model's.
 *
 * The event source sits inside a streaming turn, so this half of the chain owns both promises the contract
 * makes: `emit` returns as soon as the event is queued, and a database that is slow, down, or rejecting a
 * row never reaches the answer being streamed. Rows are written in batches because a tool call is a small
 * event at a high rate, and one `INSERT` per call would put its cost on every call.
 *
 * When the queue is full the newest event is dropped and counted rather than the backlog grown. A full
 * queue means hundreds of calls per second; a missing counter is the cheaper failure. The bound that policy
 * gives is on the number of queued events, not on their bytes: a body is cut in `toRow()` when the batch it
 * belongs to is written, not when `emit` accepts it, so the peak this class holds is `queue-capacity`
 * multiplied by one event's own payload rather than a constant the configuration keeps flat.
 */
@Component
class ToolInvocationAdaptorImpl(
    private val toolInvocationLogMapper: ToolInvocationLogMapper,
    @Value("\${harness.metrics.invocation.queue-capacity:512}") queueCapacity: Int,
    @Value("\${harness.metrics.invocation.batch-size:64}") private val batchSize: Int,
    @Value("\${harness.metrics.invocation.flush-interval-ms:200}") private val flushIntervalMs: Long,
    @Value("\${harness.metrics.invocation.capture-payload:true}") private val capturePayload: Boolean,
    @Value("\${harness.metrics.invocation.capture-max-chars:2000}") private val captureMaxChars: Int,
) : ToolInvocationAdaptor {

    private val log = LoggerFactory.getLogger(ToolInvocationAdaptorImpl::class.java)
    private val queue = ArrayBlockingQueue<ToolInvocationEvent>(queueCapacity)

    /** Counted rather than silently discarded: "the page is empty" needs a number proving the queue overflowed. */
    private val dropped = AtomicLong()
    internal val droppedCount: Long get() = dropped.get()

    @Volatile
    private var running = true

    @Volatile
    private var writer: Thread? = null

    override fun emit(event: ToolInvocationEvent) {
        // `running` is consulted here because `shutdown()` has already done its drain: an event accepted now
        // sits in a queue no thread consumes any more, which is a silent loss and no number either — the
        // exact thing this counter exists to remove. It joins the full-queue path rather than a counter of
        // its own, so a page that is empty still has one figure to explain it.
        if (running && queue.offer(event)) return
        val total = dropped.incrementAndGet()
        if (total == 1L || total % DROP_LOG_EVERY == 0L) {
            log.warn("Tool invocation event for '{}' in session {} dropped ({} dropped so far)", event.toolName, event.sessionId, total)
        }
    }

    /**
     * Started by the container, not by the constructor: the writing behaviour is asserted without any test
     * having to race a thread for it.
     */
    @PostConstruct
    fun startWriter() {
        writer = Thread(::pump, "tool-invocation-writer").apply { isDaemon = true }
        writer?.start()
    }

    private fun pump() {
        val batch = ArrayList<ToolInvocationEvent>(batchSize)
        while (running) {
            try {
                // The timeout is what commits a partial batch: a session that made three calls and went quiet
                // must not leave them queued until the next one arrives.
                val first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch += first
                    queue.drainTo(batch, batchSize - batch.size)
                }
                if (shouldFlush(batch.size, first == null)) {
                    write(batch)
                    batch.clear()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                // The batch is dropped, not retried: a writer that retried the same broken statement would
                // spin against a database that is down, and the events behind it are counters.
                log.warn("Tool invocation batch of {} row(s) was not written", batch.size, e)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) write(batch)
    }

    /**
     * The commit decision [pump] reaches on every pass, readable on its own: a batch is written when it is
     * non-empty and either [timedOut] says the poll gave up waiting or [size] has filled `batchSize`.
     *
     * Extracted rather than left inline because no test can reach the worker loop — it never starts a thread,
     * and a timing assumption in the suite is the wrong price for pinning this.
     */
    internal fun shouldFlush(
        size: Int,
        timedOut: Boolean,
    ): Boolean = size > 0 && (timedOut || size >= batchSize)

    /**
     * Take up to a batch out of the queue and write it; the seam the worker loop, `shutdown()` and the tests
     * all use. The return value is the number of events taken, so a caller can loop until it reaches zero —
     * it is not a count of rows the database accepted, which the caller cannot act on either way.
     */
    internal fun drainAndFlush(): Int {
        val batch = ArrayList<ToolInvocationEvent>(batchSize)
        queue.drainTo(batch, batchSize)
        if (batch.isEmpty()) return 0
        return try {
            write(batch)
        } catch (e: Exception) {
            log.warn("Tool invocation batch of {} row(s) was not written", batch.size, e)
            batch.size
        }
    }

    private fun write(batch: List<ToolInvocationEvent>): Int {
        val rows = batch.map { toRow(it) }
        toolInvocationLogMapper.batchInsert(rows)
        return rows.size
    }

    private fun toRow(event: ToolInvocationEvent): ToolInvocationLog {
        val end = ofEpoch(event.endEpochMilli)
        return ToolInvocationLog().apply {
            tenantId = event.tenantId
            agentId = event.agentId
            sessionId = event.sessionId
            userId = event.userId
            kind = event.kind
            // Clamped to the column without a marker: an MCP server's own tool name, or one a model invented,
            // is longer than varchar(255) only until it fails the statement and takes the other 63 counters of
            // the batch with it. A name no registry could have declared is better shortened than fatal, and
            // this column is part of the aggregate's unique key, so a marked name would open a third bucket.
            // The clamp indexes UTF-16, so it can stop between the two halves of an astral character and leave
            // a lone high surrogate behind — the same failure `truncate()` already guards, with the same rule.
            // `truncate()` is not reused here because it appends the marker this column must not carry.
            toolName = event.toolName.take(TOOL_NAME_MAX_CHARS).let { if (it.isNotEmpty() && Character.isHighSurrogate(it.last())) it.dropLast(1) else it }
            mcpId = event.mcpId
            cliId = event.cliId
            outcome = event.outcome
            errorMessage = truncate(event.errorMessage, MAX_ERROR_CHARS)
            argsJson = if (capturePayload) truncate(event.argsJson, captureMaxChars) else null
            resultExcerpt = if (capturePayload) truncate(event.resultText, captureMaxChars) else null
            durationMs = (event.endEpochMilli - event.startEpochMilli).coerceAtLeast(0L)
            startTime = ofEpoch(event.startEpochMilli)
            endTime = end
            ts = end
        }
    }

    private fun ofEpoch(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    /** Cut to [max] with a marker, so a reader can tell a long body from a short one. */
    private fun truncate(
        text: String?,
        max: Int,
    ): String? {
        if (text == null || text.length <= max) return text
        val cut = text.take(max)
        // The cut lands on a UTF-16 index, so it can stop between the two halves of an astral character and
        // leave a lone high surrogate behind — the one thing that makes the column reject the row, taking the
        // rest of the batch with it. Drop the half character rather than the whole row.
        val body = if (Character.isHighSurrogate(cut.last())) cut.dropLast(1) else cut
        return body + TRUNCATION_SUFFIX
    }

    @PreDestroy
    fun shutdown() {
        running = false
        writer?.interrupt()
        // Whatever is queued is counters, not state — but a graceful stop still owes them a write, and it is
        // done here rather than awaited on the worker so the promise holds without a timing assumption.
        while (drainAndFlush() > 0) {
            // Bounded above by ceil(queued / batchSize): `emit` refuses to queue once `running` is false, so the
            // queue this loop drains cannot grow while the stop is running. The one exception is a producer that
            // had already read `running` as true before the flip and offers after it: at most that one event
            // lands in a queue no longer drained, and it is written neither by this loop nor counted as dropped
            // — a known window, not a guarantee.
        }
    }

    companion object {
        private const val DROP_LOG_EVERY = 50L
        private const val TRUNCATION_SUFFIX = "…(truncated)"

        /** `error_message` is varchar(512); this keeps the marker inside the column whatever the payload limit says. */
        private const val MAX_ERROR_CHARS = 500

        /** `tool_name` is varchar(255), clamped without a marker because the column is part of an aggregate key. */
        private const val TOOL_NAME_MAX_CHARS = 255
    }
}
