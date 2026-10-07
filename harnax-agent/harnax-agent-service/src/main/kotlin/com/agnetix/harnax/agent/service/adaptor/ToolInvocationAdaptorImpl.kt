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
    @Value("\${harness.metrics.invocation.batch-size:64}") batchSize: Int,
    @Value("\${harness.metrics.invocation.flush-interval-ms:200}") flushIntervalMs: Long,
    @Value("\${harness.metrics.invocation.capture-payload:true}") private val capturePayload: Boolean,
    @Value("\${harness.metrics.invocation.capture-max-chars:2000}") captureMaxChars: Int,
) : ToolInvocationAdaptor {

    private val log = LoggerFactory.getLogger(ToolInvocationAdaptorImpl::class.java)

    /**
     * Every numeric tuning knob is clamped into its legal band here instead of failing the boot, for two
     * reasons worth keeping next to the code: this service carries the chat traffic and the metrics it writes
     * are a secondary concern, so an env that lost a digit must not keep a pod from starting; and none of the
     * out-of-band values is loud about itself — they degrade into a queue that never gets built, a batch that
     * never lands, a worker that spins, or a payload that takes its whole batch down with it, all of which
     * read as "the page is empty" rather than as a misconfiguration. A clamp moves the value and says so on
     * the log, so the number that is actually in effect is at least discoverable.
     *
     * The bounds are not round numbers: each one is the width of the failure it prevents, documented at the
     * constant.
     */
    private val capacity: Int = clampToBand("harness.metrics.invocation.queue-capacity", queueCapacity, MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY)
    private val batchLimit: Int = clampToBand("harness.metrics.invocation.batch-size", batchSize, MIN_BATCH_SIZE, MAX_BATCH_SIZE)

    /** The timeout an idle pass blocks on; the queue's own bound, not a per-call wait the loop forgets to use. */
    internal val idleWaitMillis: Long = clampToBand("harness.metrics.invocation.flush-interval-ms", flushIntervalMs, MIN_FLUSH_INTERVAL_MS, MAX_FLUSH_INTERVAL_MS)

    /** How many characters of a body reach the column; clamped because the column is counted in bytes. */
    private val payloadMaxChars: Int = clampToBand("harness.metrics.invocation.capture-max-chars", captureMaxChars, MIN_CAPTURE_MAX_CHARS, MAX_CAPTURE_MAX_CHARS)
    private val queue = ArrayBlockingQueue<ToolInvocationEvent>(capacity)

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
        val batch = ArrayList<ToolInvocationEvent>(batchLimit)
        while (running) {
            try {
                // The timeout is what commits a partial batch: a session that made three calls and went quiet
                // must not leave them queued until the next one arrives.
                val first = awaitFirstEvent()
                if (first != null) {
                    batch += first
                    queue.drainTo(batch, batchLimit - batch.size)
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
     * Block until the next event arrives or [idleWaitMillis] goes by, whichever comes first.
     *
     * One wait carries both promises: it is what commits a partial batch after a session goes quiet, and it is
     * what keeps an idle pass from returning instantly and turning the loop into a spin against an empty queue.
     * It is a seam rather than inline because no unit test starts the worker thread, and the timeout an idle
     * pass really blocks on is the one number worth pinning.
     */
    internal fun awaitFirstEvent(): ToolInvocationEvent? = queue.poll(idleWaitMillis, TimeUnit.MILLISECONDS)

    /**
     * The commit decision [pump] reaches on every pass, readable on its own: a batch is written when it is
     * non-empty and either [timedOut] says the poll gave up waiting or [size] has filled the batch size in
     * effect.
     *
     * Extracted rather than left inline because no test can reach the worker loop — it never starts a thread,
     * and a timing assumption in the suite is the wrong price for pinning this.
     */
    internal fun shouldFlush(
        size: Int,
        timedOut: Boolean,
    ): Boolean = size > 0 && (timedOut || size >= batchLimit)

    /**
     * Take up to a batch out of the queue and write it; the seam the worker loop, `shutdown()` and the tests
     * all use. The return value is the number of events taken, so a caller can loop until it reaches zero —
     * it is not a count of rows the database accepted, which the caller cannot act on either way.
     */
    internal fun drainAndFlush(): Int {
        val batch = ArrayList<ToolInvocationEvent>(batchLimit)
        queue.drainTo(batch, batchLimit)
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
            argsJson = if (capturePayload) truncate(event.argsJson, payloadMaxChars) else null
            resultExcerpt = if (capturePayload) truncate(event.resultText, payloadMaxChars) else null
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

    /**
     * Move [raw] into [min]..[max] and log the move: a silent clamp would leave the reader with a configured
     * number that is not the number in effect, which is the same trap as the out-of-band value itself, only
     * quieter.
     */
    private fun clampToBand(
        name: String,
        raw: Int,
        min: Int,
        max: Int,
    ): Int {
        val clamped = raw.coerceIn(min, max)
        if (clamped != raw) {
            log.warn("Tool invocation metric {} is set to {}, outside the legal band {}..{}; using {}", name, raw, min, max, clamped)
        }
        return clamped
    }

    private fun clampToBand(
        name: String,
        raw: Long,
        min: Long,
        max: Long,
    ): Long {
        val clamped = raw.coerceIn(min, max)
        if (clamped != raw) {
            log.warn("Tool invocation metric {} is set to {}, outside the legal band {}..{}; using {}", name, raw, min, max, clamped)
        }
        return clamped
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

        /**
         * `ArrayBlockingQueue` refuses a capacity below 1 out of hand, so the floor is what keeps one lost
         * digit in an env from stopping the service. The ceiling is a heap bound rather than a correctness
         * one: this queue's worst case is `capacity` multiplied by one event's own payload, because a body is
         * cut in [toRow] when its batch is written and not when [emit] accepts it — 65536 is the largest depth
         * that stays a rounding error against a heap sized for the chat traffic.
         */
        private const val MIN_QUEUE_CAPACITY = 1
        private const val MAX_QUEUE_CAPACITY = 65536

        /**
         * The floor is 1 because every smaller value loses rows in a different way: a negative one throws out
         * of `ArrayList`, and that allocation sits outside `pump`'s try block, so the worker thread dies on its
         * first pass and nothing is written again; a zero one makes `drainTo` take nothing, so `shutdown()`
         * reports an empty queue while the events stay in it. The ceiling is a statement-size bound — 1024 rows
         * is already more than one full queue can supply, and every row in a batch carries its own body, cut
         * only when the batch is written.
         */
        private const val MIN_BATCH_SIZE = 1
        private const val MAX_BATCH_SIZE = 1024

        /**
         * The floor is the smallest wait that is still a park rather than a look at an empty queue: below it the
         * idle pass stops blocking and the writer becomes a spin that burns a core while reporting nothing. The
         * ceiling is a minute because that is how long a quiet session's rows stay invisible on the page after
         * the last call, and a longer wait would make the lag bigger than the hourly rollup it feeds.
         */
        private const val MIN_FLUSH_INTERVAL_MS = 10L
        private const val MAX_FLUSH_INTERVAL_MS = 60_000L

        /**
         * The floor is 1 because `truncate` opens with `text.length <= max`, which neither 0 nor a negative
         * limit can satisfy: a negative one then reaches `take(-1)` and throws, and the throw is caught as a
         * broken batch, so every counter in it is lost. A limit of 0 would store the marker alone — a body that
         * looks truncated for every call and can no longer be told apart from the NULL that
         * `capture-payload=false` leaves in the same two columns.
         *
         * The ceiling is the column, not a preference: `args_json` and `result_excerpt` are MySQL `text` at
         * 65,535 **bytes**, and a CJK payload costs 3 bytes per character in UTF-8, so the widest body that can
         * ever fit is 21,845 characters. 20,000 leaves room for the truncation marker as well; past that the
         * row fails with data-too-long, and one failed row loses the whole batch with it.
         */
        private const val MIN_CAPTURE_MAX_CHARS = 1
        private const val MAX_CAPTURE_MAX_CHARS = 20_000

        /** `error_message` is varchar(512); this keeps the marker inside the column whatever the payload limit says. */
        private const val MAX_ERROR_CHARS = 500

        /** `tool_name` is varchar(255), clamped without a marker because the column is part of an aggregate key. */
        private const val TOOL_NAME_MAX_CHARS = 255
    }
}
