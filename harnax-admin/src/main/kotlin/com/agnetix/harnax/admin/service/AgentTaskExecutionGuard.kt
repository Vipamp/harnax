package com.agnetix.harnax.admin.service

import com.agnetix.harnax.mapper.AgentTaskExecutionMapper
import com.agnetix.harnax.entity.AgentTaskExecution
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Execution guard service for multi-instance deployment.
 * Uses database unique constraint to prevent duplicate task execution.
 */
@Service
class AgentTaskExecutionGuard(
    private val executionMapper: AgentTaskExecutionMapper,
    @Value("\${agent-task.instance-id:#{null}}") private val configuredInstanceId: String?
) {
    private val log = LoggerFactory.getLogger(AgentTaskExecutionGuard::class.java)

    private val instanceId: String by lazy {
        configuredInstanceId ?: "${getHostName()}-${UUID.randomUUID().toString().substring(0, 8)}"
    }

    /**
     * Try to acquire execution lock for a task at a specific trigger time.
     * Returns true if lock acquired (this instance should execute), false otherwise.
     */
    fun tryAcquireLock(taskId: Long, triggerTime: LocalDateTime): Boolean {
        return try {
            val execution = AgentTaskExecution().apply {
                this.taskId = taskId
                this.triggerTime = triggerTime.truncatedTo(ChronoUnit.SECONDS)
                this.instanceId = instanceId
                this.status = 0 // running
                this.createTime = LocalDateTime.now()
            }
            executionMapper.insert(execution)
            log.debug("Acquired execution lock for task {} at {}", taskId, triggerTime)
            true
        } catch (e: Exception) {
            // Unique constraint violation means another instance already has the lock
            log.debug("Failed to acquire execution lock for task {} at {}: {}", taskId, triggerTime, e.message)
            false
        }
    }

    /**
     * Update execution status after task completes.
     */
    fun updateExecutionStatus(taskId: Long, triggerTime: LocalDateTime, success: Boolean, startTime: LocalDateTime, endTime: LocalDateTime) {
        try {
            val execution = executionMapper.selectByTaskIdAndTriggerTime(taskId, triggerTime.truncatedTo(ChronoUnit.SECONDS))
            if (execution != null) {
                executionMapper.updateStatus(
                    execution.id,
                    if (success) 1 else 2,
                    startTime,
                    endTime
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to update execution status for task {}: {}", taskId, e.message)
        }
    }

    /**
     * Clean up old execution records (older than specified days).
     */
    fun cleanupOldExecutions(retentionDays: Int = 7) {
        try {
            val beforeTime = LocalDateTime.now().minusDays(retentionDays.toLong())
            val deleted = executionMapper.deleteOldExecutions(beforeTime)
            if (deleted > 0) {
                log.info("Cleaned up {} old execution records", deleted)
            }
        } catch (e: Exception) {
            log.warn("Failed to cleanup old executions: {}", e.message)
        }
    }

    private fun getHostName(): String {
        return try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "unknown"
        }
    }
}
