package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.entity.AgentTaskExecution
import com.agnetix.harnax.mapper.AgentTaskExecutionMapper
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
    @Value("\${scheduler.instance-id:#{null}}") private val configuredInstanceId: String?,
    /**
     * The same key `SchedulerServiceImpl` and `RouterClient` read: how long an execution may take, how
     * long until its log row counts as a zombie and how long until its lock row counts as leaked have to
     * be one number told three ways, or the sweep frees a lock whose execution is still running.
     */
    @Value("\${scheduler.timeout-seconds:300}") private val executionTimeoutSeconds: Int,
) {
    private val log = LoggerFactory.getLogger(AgentTaskExecutionGuard::class.java)

    private val instanceId: String by lazy {
        configuredInstanceId ?: "${getHostName()}-${UUID.randomUUID().toString().substring(0, 8)}"
    }

    /**
     * Try to acquire execution lock for a task at a specific trigger time.
     * Returns true if lock acquired (this instance should execute), false otherwise.
     */
    fun tryAcquireLock(taskId: Long, triggerTime: LocalDateTime): Boolean = try {
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
                    endTime,
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

    /**
     * Release locks whose holder is gone. A row still at status 0 after twice the execution timeout
     * cannot have a live owner — the execution would have been reaped by then — and leaving it in place
     * blocks that (task_id, trigger_time) from ever being delivered again.
     *
     * Twice, not once: the log-side sweep runs at 1.5x the timeout with its own grace window, and a
     * lock reaped before its execution has honestly given up would let the same trigger run twice.
     */
    fun cleanupLeakedLocks(): Int = try {
        val deleted = executionMapper.deleteStaleRunning(
            LocalDateTime.now().minusSeconds(executionTimeoutSeconds * 2L),
        )
        if (deleted > 0) {
            log.info("Released {} execution lock(s) whose holder is gone", deleted)
        }
        deleted
    } catch (e: Exception) {
        log.warn("Failed to sweep leaked execution locks: {}", e.message)
        0
    }

    private fun getHostName(): String = try {
        java.net.InetAddress.getLocalHost().hostName
    } catch (e: Exception) {
        "unknown"
    }
}
