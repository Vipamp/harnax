package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.dto.ApiCallLogPage
import com.agnetix.harnax.router.dto.ApiCallLogQuery
import com.agnetix.harnax.router.entity.ApiCallLog
import com.agnetix.harnax.router.mapper.ApiCallLogMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

@Service
class ApiCallLogService(
    private val apiCallLogMapper: ApiCallLogMapper,
) {

    private val log = LoggerFactory.getLogger(ApiCallLogService::class.java)
    private val buffer = ConcurrentLinkedQueue<ApiCallLog>()

    // Track buffer size with AtomicLong to avoid O(n) traversal.
    // ConcurrentLinkedQueue.size() is O(1) but still contended; an external counter is more stable.
    private val bufferSize = AtomicLong(0)

    // Cumulative dropped entry count (for monitoring).
    private val droppedCount = AtomicLong(0)

    // Rows the database itself refused, after the batch retry narrowed them down.
    private val poisonedCount = AtomicLong(0)

    companion object {
        private const val BATCH_SIZE = 50

        // Hard upper bound for the buffer: drop new entries when exceeded to prevent OOM
        // if MySQL becomes unavailable. ~10MB for 10_000 entries, well below the heap warning threshold.
        private const val MAX_BUFFER_SIZE = 10_000

        /**
         * Column widths from `db/migration/V1__create_session_router_tables.sql`, with headroom.
         *
         * MySQL runs with STRICT_TRANS_TABLES, where an oversized value is not a warning but a
         * rejected statement — and the statement is the whole 50-row batch. Without truncation one
         * long error message would therefore bury 49 unrelated rows, every 5 seconds, forever:
         * a failed batch goes back onto the queue unchanged, so the same row poisons it again.
         */
        private const val MAX_ERROR_MESSAGE = 1000 // VARCHAR(1024)
        private const val MAX_ENDPOINT = 250 // VARCHAR(256)
        private const val MAX_CALLER_ID = 128
        private const val MAX_SESSION_ID = 128
        private const val MAX_INSTANCE_ID = 128
        private const val MAX_AGENT_NAME = 128
        private const val MAX_MODEL_NAME = 128
        private const val MAX_REQUEST_ID = 64
        private const val MAX_METHOD = 16
        private const val MAX_CALLER_TYPE = 32
        private const val MAX_REQUEST_TYPE = 32
    }

    /** Non-null columns keep their nullness out of the picture. */
    private fun String.cut(limit: Int): String = take(limit)

    private fun String?.cutOpt(limit: Int): String? = this?.take(limit)

    fun record(entry: ApiCallLog) {
        val current = bufferSize.incrementAndGet()
        if (current > MAX_BUFFER_SIZE) {
            // Over the limit: drop the new entry to avoid OOM.
            bufferSize.decrementAndGet()
            val dropped = droppedCount.incrementAndGet()
            // Log a warning every 100 drops to avoid log flooding.
            if (dropped % 100 == 1L) {
                log.warn(
                    "ApiCallLog buffer full (size={} > {}), dropping new entry. " +
                        "Total dropped so far: {}. Check MySQL connectivity.",
                    current,
                    MAX_BUFFER_SIZE,
                    dropped,
                )
            }
            return
        }
        buffer.add(entry)
        if (current >= BATCH_SIZE) {
            flush()
        }
    }

    @Scheduled(fixedDelay = 5000)
    fun scheduledFlush() {
        flush()
    }

    @PreDestroy
    fun onShutdown() {
        flush()
    }

    private fun flush() {
        val batch = mutableListOf<ApiCallLog>()
        while (batch.size < BATCH_SIZE) {
            val item = buffer.poll() ?: break
            batch.add(item)
            bufferSize.decrementAndGet()
        }
        if (batch.isEmpty()) return

        try {
            apiCallLogMapper.batchInsert(batch)
            log.debug("Flushed {} API call log entries", batch.size)
        } catch (e: DataIntegrityViolationException) {
            // The schema refused the data, the database is not down: retry row by row so that one
            // bad record cannot decide the fate of its 49 neighbours. Anything that still fails on
            // its own is a genuine outlier and gets counted off.
            log.warn("Batch of {} rows rejected on data grounds, retrying individually: {}", batch.size, e.message)
            retryIndividually(batch)
        } catch (e: Exception) {
            // On failure, re-enqueue the batch at the tail (addAll preserves insertion order).
            // If the buffer is already full at this point, subsequent record() calls will hit
            // the drop logic; we simply re-add here and rely on the next record() to trigger flush.
            log.error("Failed to flush {} API call log entries: {}", batch.size, e.message, e)
            // Before re-enqueue, check the limit and evict the oldest entries if it would overflow.
            val reAddSize = batch.size.toLong()
            val newSize = bufferSize.addAndGet(reAddSize)
            if (newSize > MAX_BUFFER_SIZE) {
                // Drop the overflow (newer entries are kept).
                val overflow = newSize - MAX_BUFFER_SIZE
                var dropped = 0
                while (dropped < overflow && buffer.poll() != null) {
                    bufferSize.decrementAndGet()
                    dropped++
                }
                log.warn(
                    "Re-enqueue would overflow buffer, dropped {} oldest entries. " +
                        "Current size: {}",
                    dropped,
                    bufferSize.get(),
                )
            }
            buffer.addAll(batch)
        }
    }

    /**
     * Fallback for a batch the database rejected on data grounds: insert one row at a time and drop
     * only the rows that fail alone, which are the ones actually at fault.
     */
    private fun retryIndividually(batch: List<ApiCallLog>) {
        var saved = 0
        var refused = 0
        for (row in batch) {
            try {
                apiCallLogMapper.insert(row)
                saved++
            } catch (rowError: Exception) {
                refused++
                val total = poisonedCount.incrementAndGet()
                if (total % 100 == 1L) {
                    log.warn(
                        "Dropped an api_call_log row the database refuses (endpoint={}, session={}, {} such rows " +
                            "so far): {}",
                        row.endpoint,
                        row.sessionId,
                        total,
                        rowError.message,
                    )
                }
            }
        }
        log.info("Individual retry saved {} of {} rows and dropped {} refused by the schema", saved, batch.size, refused)
    }

    /**
     * Current buffer size (for monitoring/testing only).
     */
    fun currentBufferSize(): Long = bufferSize.get()

    /**
     * Cumulative dropped entry count (for monitoring/testing only).
     */
    fun droppedCount(): Long = droppedCount.get()

    /**
     * Cumulative count of rows the database rejected on data grounds (for monitoring/testing only).
     * A growing number here means some field is arriving oversized despite the truncation above.
     */
    fun poisonedCount(): Long = poisonedCount.get()

    /**
     * Query call logs with the given filters, paginated.
     * Used by the monitor UI; queries are unbuffered and read directly from MySQL.
     */
    fun query(query: ApiCallLogQuery): ApiCallLogPage {
        val items = apiCallLogMapper.query(query)
        val total = apiCallLogMapper.count(query)
        return ApiCallLogPage(items = items, total = total, limit = query.limit, offset = query.offset)
    }

    fun buildLogEntry(
        callerId: String,
        callerType: String,
        tenantId: Long?,
        sessionId: String?,
        agentId: Long?,
        agentName: String?,
        modelId: Long?,
        modelName: String?,
        endpoint: String,
        method: String,
        requestType: String?,
        statusCode: Int,
        success: Boolean,
        errorMessage: String?,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        instanceId: String?,
        requestId: String?,
    ): ApiCallLog = ApiCallLog().apply {
        this.callerId = callerId.cut(MAX_CALLER_ID)
        this.callerType = callerType.cut(MAX_CALLER_TYPE)
        this.tenantId = tenantId
        this.sessionId = sessionId.cutOpt(MAX_SESSION_ID)
        this.agentId = agentId
        this.agentName = agentName.cutOpt(MAX_AGENT_NAME)
        this.modelId = modelId
        this.modelName = modelName.cutOpt(MAX_MODEL_NAME)
        this.endpoint = endpoint.cut(MAX_ENDPOINT)
        this.method = method.cut(MAX_METHOD)
        this.requestType = requestType.cutOpt(MAX_REQUEST_TYPE)
        this.statusCode = statusCode
        this.success = if (success) 1 else 0
        this.errorMessage = errorMessage.cutOpt(MAX_ERROR_MESSAGE)
        this.startTime = startTime
        this.endTime = endTime
        this.durationMs = Duration.between(startTime, endTime).toMillis()
        this.instanceId = instanceId.cutOpt(MAX_INSTANCE_ID)
        this.requestId = requestId.cutOpt(MAX_REQUEST_ID)
    }
}
