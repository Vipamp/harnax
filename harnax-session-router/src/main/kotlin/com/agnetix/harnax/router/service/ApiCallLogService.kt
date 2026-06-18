package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.entity.ApiCallLog
import com.agnetix.harnax.router.mapper.ApiCallLogMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue

@Service
class ApiCallLogService(
    private val apiCallLogMapper: ApiCallLogMapper,
) {

    private val log = LoggerFactory.getLogger(ApiCallLogService::class.java)
    private val buffer = ConcurrentLinkedQueue<ApiCallLog>()

    companion object {
        private const val BATCH_SIZE = 50
        private val FLUSH_INTERVAL: Duration = Duration.ofSeconds(5)
    }

    fun record(entry: ApiCallLog) {
        buffer.add(entry)
        if (buffer.size >= BATCH_SIZE) {
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
        }
        if (batch.isEmpty()) return

        try {
            apiCallLogMapper.batchInsert(batch)
            log.debug("Flushed {} API call log entries", batch.size)
        } catch (e: Exception) {
            log.error("Failed to flush {} API call log entries: {}", batch.size, e.message, e)
            // Re-enqueue failed entries (best effort)
            buffer.addAll(batch)
        }
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
