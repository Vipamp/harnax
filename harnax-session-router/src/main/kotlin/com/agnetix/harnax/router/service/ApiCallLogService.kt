package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.dto.ApiCallLogPage
import com.agnetix.harnax.router.dto.ApiCallLogQuery
import com.agnetix.harnax.router.entity.ApiCallLog
import com.agnetix.harnax.router.mapper.ApiCallLogMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
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

    companion object {
        private const val BATCH_SIZE = 50

        // Hard upper bound for the buffer: drop new entries when exceeded to prevent OOM
        // if MySQL becomes unavailable. ~10MB for 10_000 entries, well below the heap warning threshold.
        private const val MAX_BUFFER_SIZE = 10_000
    }

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
     * Current buffer size (for monitoring/testing only).
     */
    fun currentBufferSize(): Long = bufferSize.get()

    /**
     * Cumulative dropped entry count (for monitoring/testing only).
     */
    fun droppedCount(): Long = droppedCount.get()

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
        this.callerId = callerId
        this.callerType = callerType
        this.tenantId = tenantId
        this.sessionId = sessionId
        this.agentId = agentId
        this.agentName = agentName
        this.modelId = modelId
        this.modelName = modelName
        this.endpoint = endpoint
        this.method = method
        this.requestType = requestType
        this.statusCode = statusCode
        this.success = if (success) 1 else 0
        this.errorMessage = errorMessage
        this.startTime = startTime
        this.endTime = endTime
        this.durationMs = Duration.between(startTime, endTime).toMillis()
        this.instanceId = instanceId
        this.requestId = requestId
    }
}
