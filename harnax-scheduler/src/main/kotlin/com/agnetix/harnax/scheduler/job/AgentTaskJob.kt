package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

class AgentTaskJob : Job {

    private val log = LoggerFactory.getLogger(AgentTaskJob::class.java)

    // Quartz jobs are not Spring-managed, so we get beans from the scheduler context
    private fun getRouterClient(context: JobExecutionContext): RouterClient = context.scheduler.context["routerClient"] as RouterClient

    private fun getExecutionGuard(context: JobExecutionContext): AgentTaskExecutionGuard = context.scheduler.context["executionGuard"] as AgentTaskExecutionGuard

    private fun getTaskLogMapper(context: JobExecutionContext): AgentTaskLogMapper = context.scheduler.context["taskLogMapper"] as AgentTaskLogMapper

    override fun execute(context: JobExecutionContext) {
        val task = context.jobDetail.jobDataMap["agentTask"] as? AgentTask
        if (task == null) {
            log.error("Agent task not found in job data map")
            return
        }

        val triggerTime = LocalDateTime.now()
        val executionGuard = getExecutionGuard(context)

        // Multi-instance guard: try to acquire execution lock
        if (!executionGuard.tryAcquireLock(task.id, triggerTime)) {
            log.info("Task {} already being executed by another instance, skipping", task.id)
            return
        }

        log.info("Starting agent task execution: id={}, name={}, agentId={}", task.id, task.name, task.agentId)

        val routerClient = getRouterClient(context)
        val taskLogMapper = getTaskLogMapper(context)

        val taskLog = AgentTaskLog().apply {
            taskId = task.id
            taskName = task.name
            prompt = task.prompt
            startTime = LocalDateTime.now()
            creator = task.creator
            createTime = LocalDateTime.now()
            status = 3 // running
        }

        // Generate task sessionId: task-{taskId}-{uuid}
        val sessionId = "task-${task.id}-${UUID.randomUUID()}"
        taskLog.sessionId = sessionId

        // Insert running log immediately so it's visible on the page
        taskLogMapper.insert(taskLog)
        log.info("Inserted running task log: id={}, taskId={}", taskLog.id, task.id)

        try {
            // 1. Call router to execute agent task
            val response = routerClient.chat(sessionId, task.prompt)

            // 2. Record success
            taskLog.response = response.content
            taskLog.tokenUsage = if (response.tokenUsage != null) {
                response.tokenUsage.toString()
            } else {
                ""
            }
            taskLog.status = 1 // success

            log.info("Agent task executed successfully: id={}, name={}", task.id, task.name)
        } catch (e: Exception) {
            log.error("Agent task execution failed: id={}, name={}, error={}", task.id, task.name, e.message, e)
            taskLog.status = 0 // failed
            taskLog.errorInfo = e.message?.take(4000) ?: "Unknown error"
        } finally {
            // 3. Clear agent cache on agent-service
            routerClient.clearSession(sessionId)

            val endTime = LocalDateTime.now()
            taskLog.endTime = endTime
            taskLog.durationMs = if (taskLog.startTime != null) {
                Duration.between(taskLog.startTime, endTime).toMillis()
            } else {
                0
            }

            // 4. Update task log with final result
            try {
                taskLogMapper.updateById(taskLog)
                log.info("Updated agent task log: id={}, status={}, durationMs={}", taskLog.id, taskLog.status, taskLog.durationMs)
            } catch (e: Exception) {
                log.error("Failed to update agent task log: id={}, error={}", taskLog.id, e.message, e)
            }

            // Update execution guard status
            executionGuard.updateExecutionStatus(
                task.id,
                triggerTime,
                taskLog.status == 1,
                taskLog.startTime ?: endTime,
                endTime,
            )
        }
    }
}
