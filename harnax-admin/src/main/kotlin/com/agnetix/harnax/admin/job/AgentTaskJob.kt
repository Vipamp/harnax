package com.agnetix.harnax.admin.job

import com.agnetix.harnax.admin.client.RouterClient
import com.agnetix.harnax.admin.service.AgentTaskExecutionGuard
import com.agnetix.harnax.admin.service.AgentTaskLogService
import com.agnetix.harnax.admin.service.SessionService
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

@Component
class AgentTaskJob : Job {

    private val log = LoggerFactory.getLogger(AgentTaskJob::class.java)

    @Autowired
    private lateinit var sessionService: SessionService

    @Autowired
    private lateinit var routerClient: RouterClient

    @Autowired
    private lateinit var agentTaskLogService: AgentTaskLogService

    @Autowired
    private lateinit var executionGuard: AgentTaskExecutionGuard

    override fun execute(context: JobExecutionContext) {
        val task = context.jobDetail.jobDataMap["agentTask"] as? AgentTask
        if (task == null) {
            log.error("Agent task not found in job data map")
            return
        }

        val triggerTime = LocalDateTime.now()

        // Multi-instance guard: try to acquire execution lock
        if (!executionGuard.tryAcquireLock(task.id, triggerTime)) {
            log.info("Task {} already being executed by another instance, skipping", task.id)
            return
        }

        log.info("Starting agent task execution: id={}, name={}, agentId={}", task.id, task.name, task.agentId)

        val taskLog = AgentTaskLog().apply {
            taskId = task.id
            taskName = task.name
            prompt = task.prompt
            startTime = LocalDateTime.now()
            creator = task.creator
            createTime = LocalDateTime.now()
        }

        var sessionDbId: Long? = null

        try {
            // 1. Create temporary session
            val session = sessionService.createForAgent(task.agentId, task.creator)
            sessionDbId = session.id
            taskLog.sessionId = session.sessionId

            log.info("Created temporary session: id={}, sessionId={}", session.id, session.sessionId)

            // 2. Call router to execute agent task
            val response = routerClient.chat(session.sessionId, task.prompt)

            // 3. Record success
            taskLog.response = response.content ?: ""
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
            // 4. Clean up session (always, whether success or failure)
            if (sessionDbId != null) {
                try {
                    sessionService.deleteSession(sessionDbId)
                    log.info("Deleted temporary session: id={}", sessionDbId)
                } catch (e: Exception) {
                    log.warn("Failed to delete temporary session: id={}, error={}", sessionDbId, e.message)
                }
            }

            val endTime = LocalDateTime.now()
            taskLog.endTime = endTime
            taskLog.durationMs = if (taskLog.startTime != null) {
                Duration.between(taskLog.startTime, endTime).toMillis()
            } else {
                0
            }

            // Update execution guard status
            executionGuard.updateExecutionStatus(
                task.id,
                triggerTime,
                taskLog.status == 1,
                taskLog.startTime ?: endTime,
                endTime
            )

            try {
                agentTaskLogService.save(taskLog)
                log.info("Saved agent task log: taskId={}, status={}, durationMs={}", taskLog.taskId, taskLog.status, taskLog.durationMs)
            } catch (e: Exception) {
                log.error("Failed to save agent task log: taskId={}, error={}", taskLog.taskId, e.message, e)
            }
        }
    }
}
