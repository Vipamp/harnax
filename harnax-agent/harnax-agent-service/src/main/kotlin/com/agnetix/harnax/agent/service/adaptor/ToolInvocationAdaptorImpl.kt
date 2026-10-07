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
 * queue means hundreds of calls per second; a missing counter is the cheaper failure, and the bound is what
 * keeps memory flat when the database stays unreachable.
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
    private var writer: Thread? = null

    override fun emit(event: ToolInvocationEvent) {
        if (queue.offer(event)) return
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
                if (batch.isNotEmpty() && (first == null || batch.size >= batchSize)) {
                    write(batch)
                    batch.clear()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                // The batch is dropped, not retried: a writer that retried the same broken statement would
                // spin against a database that is down, and the events behind it are counters.
                log.warn("Tool invocation batch of {} row(s) was not written: {}", batch.size, e.message)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) write(batch)
    }

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
            log.warn("Tool invocation batch of {} row(s) was not written: {}", batch.size, e.message)
            batch.size
        }
    }

    private fun write(batch: List<ToolInvocationEvent>): Int {
        if (batch.isEmpty()) return 0
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
            toolName = event.toolName
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
        return text.take(max) + TRUNCATION_SUFFIX
    }

    @PreDestroy
    fun shutdown() {
        running = false
        writer?.interrupt()
        // Whatever is queued is counters, not state — but a graceful stop still owes them a write, and it is
        // done here rather than awaited on the worker so the promise holds without a timing assumption.
        while (drainAndFlush() > 0) {
            // Bounded below by the queue capacity; break out if the writer is falling behind.
        }
    }

    companion object {
        private const val DROP_LOG_EVERY = 50L
        private const val TRUNCATION_SUFFIX = "…(truncated)"

        /** `error_message` is varchar(512); this keeps the marker inside the column whatever the payload limit says. */
        private const val MAX_ERROR_CHARS = 500
    }
}
