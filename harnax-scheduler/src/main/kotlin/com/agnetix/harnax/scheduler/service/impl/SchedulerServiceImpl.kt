package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.job.AgentTaskJob
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import jakarta.annotation.PostConstruct
import org.quartz.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class SchedulerServiceImpl(
    private val schedulerFactory: SchedulerFactoryBean,
    private val agentTaskMapper: AgentTaskMapper,
    private val agentTaskLogMapper: AgentTaskLogMapper,
    private val routerClient: RouterClient,
    private val executionGuard: AgentTaskExecutionGuard,
    @Value("\${scheduler.enabled:true}") private val schedulerEnabled: Boolean,
) : SchedulerService {

    private val log = LoggerFactory.getLogger(SchedulerServiceImpl::class.java)

    /** Track running task executions for stop capability */
    private val runningTasks = ConcurrentHashMap<Long, SchedulerService.RunningTaskInfo>()

    /** Track log IDs that have been explicitly stopped by user */
    private val stoppedLogIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private val scheduler: Scheduler
        get() = schedulerFactory.scheduler

    @PostConstruct
    fun init() {
        if (!schedulerEnabled) {
            log.info("Scheduler is disabled on this instance")
            return
        }

        // Register beans in scheduler context so Quartz jobs can access them
        val schedulerContext = scheduler.context
        schedulerContext["routerClient"] = routerClient
        schedulerContext["executionGuard"] = executionGuard
        schedulerContext["taskLogMapper"] = agentTaskLogMapper
        schedulerContext["runningTasks"] = runningTasks
        schedulerContext["schedulerService"] = this

        Thread {
            try {
                Thread.sleep(3000)
                loadTasksToScheduler()
            } catch (e: Exception) {
                log.error("Failed to load agent tasks to scheduler", e)
            }
        }.start()
    }

    override fun scheduleTask(task: AgentTask) {
        val jobKey = JobKey("AgentTask_${task.id}", "AgentTaskGroup")
        val triggerKey = TriggerKey("AgentTask_${task.id}_trigger", "AgentTaskGroup")

        val jobDataMap = JobDataMap()
        jobDataMap.put("agentTask", task)
        val jobDetail = JobBuilder.newJob(AgentTaskJob::class.java)
            .withIdentity(jobKey)
            .usingJobData(jobDataMap)
            .storeDurably()
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(triggerKey)
            .forJob(jobKey)
            .withSchedule(
                CronScheduleBuilder.cronSchedule(task.cronExpression)
                    .apply {
                        if (task.concurrent == 0) {
                            withMisfireHandlingInstructionDoNothing()
                        } else {
                            withMisfireHandlingInstructionFireAndProceed()
                        }
                    },
            )
            .build()

        // Clean up any existing job first, then schedule fresh
        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey)
        }
        scheduler.scheduleJob(jobDetail, trigger)
        log.info("Scheduled agent task: id={}, name={}, cron={}", task.id, task.name, task.cronExpression)
    }

    override fun unscheduleTask(task: AgentTask) {
        val jobKey = JobKey("AgentTask_${task.id}", "AgentTaskGroup")
        scheduler.deleteJob(jobKey)
        log.info("Unscheduled agent task: id={}, name={}", task.id, task.name)
    }

    override fun loadTasksToScheduler() {
        log.info("Loading agent tasks to scheduler")

        // Step 0: Clean up stale running logs from previous crashes/restarts
        cleanupAllStaleRunningLogs()

        // Step 1: Clean up all existing Quartz jobs in AgentTaskGroup
        try {
            val existingKeys = scheduler.getJobKeys(org.quartz.impl.matchers.GroupMatcher.jobGroupEquals("AgentTaskGroup"))
            for (key in existingKeys) {
                scheduler.deleteJob(key)
            }
            if (existingKeys.isNotEmpty()) {
                log.info("Cleaned up {} existing Quartz jobs", existingKeys.size)
            }
        } catch (e: Exception) {
            log.warn("Failed to clean up existing Quartz jobs: {}", e.message)
        }

        // Step 2: Schedule all active tasks from DB
        val runningTasks = agentTaskMapper.selectRunningTasks()
        log.info("Found {} running agent tasks", runningTasks.size)

        for (task in runningTasks) {
            try {
                scheduleTask(task)
                log.info("Loaded agent task to scheduler: id={}, name={}", task.id, task.name)
            } catch (e: Exception) {
                log.error("Failed to load agent task: id={}, name={}, error={}", task.id, task.name, e.message, e)
            }
        }
    }

    override fun startTask(id: Long): Boolean {
        val task = agentTaskMapper.selectById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        // Always re-schedule and update DB, even if already marked as running
        // (handles recovery from previous broken toggles)
        scheduleTask(task)
        return agentTaskMapper.updateStatus(id, 1) > 0
    }

    override fun pauseTask(id: Long): Boolean {
        val task = agentTaskMapper.selectById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        // Always delete from Quartz and update DB (handles recovery from broken toggles)
        unscheduleTask(task)
        return agentTaskMapper.updateStatus(id, 0) > 0
    }

    override fun runTaskOnce(id: Long): Boolean {
        val task = agentTaskMapper.selectById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        // Guard: reject if task already has an active running execution
        val hasActiveExecution = hasActiveRunningLog(task)
        if (hasActiveExecution) {
            log.warn("Task {} has an active running execution, rejecting runOnce", task.id)
            throw RuntimeException("Task is already running, please wait for it to complete")
        }

        val uniqueId = java.util.UUID.randomUUID().toString().substring(0, 8)
        val jobKey = JobKey("AgentTask_${task.id}_ONCE_$uniqueId", "AgentTaskGroup_ONCE")
        val jobDataMap = JobDataMap()
        jobDataMap.put("agentTask", task)
        val jobDetail = JobBuilder.newJob(AgentTaskJob::class.java)
            .withIdentity(jobKey)
            .usingJobData(jobDataMap)
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(TriggerKey("AgentTask_${task.id}_ONCE_${uniqueId}_trigger", "AgentTaskGroup_ONCE"))
            .startNow()
            .build()

        scheduler.scheduleJob(jobDetail, trigger)
        return true
    }

    override fun triggerManually(id: Long): Boolean {
        val task = agentTaskMapper.selectById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        val triggerTime = LocalDateTime.now()

        // Guard: check for actively running logs, auto-expire stale ones (from previous crashes/restarts)
        val hasActiveExecution = hasActiveRunningLog(task)
        if (hasActiveExecution) {
            log.warn("Task {} has an active running execution, rejecting trigger", task.id)
            throw RuntimeException("Task is already running, please wait for it to complete")
        }

        // Multi-instance guard (synchronous check)
        if (!executionGuard.tryAcquireLock(task.id, triggerTime)) {
            log.info("Task {} already being executed by another instance, skipping", task.id)
            return false
        }

        log.info("Manually triggering agent task: id={}, name={}", task.id, task.name)

        // Execute asynchronously via separate thread (Spring @Async doesn't work on self-invocation)
        Thread {
            executeTaskOnce(task, triggerTime)
        }.apply {
            name = "manual-trigger-${task.id}"
            isDaemon = true
            start()
        }

        return true
    }

    /**
     * Shared task execution logic used by both Quartz jobs and manual triggers.
     * Creates task log, registers in runningTasks, calls router, handles result and cleanup.
     */
    override fun executeTaskOnce(task: AgentTask, triggerTime: LocalDateTime) {
        val taskLog = AgentTaskLog().apply {
            taskId = task.id
            taskName = task.name
            prompt = task.prompt
            startTime = LocalDateTime.now()
            creator = task.creator
            createTime = LocalDateTime.now()
            status = 3 // running
        }

        val sessionId = "task-${task.id}-${UUID.randomUUID()}"
        taskLog.sessionId = sessionId

        agentTaskLogMapper.insert(taskLog)
        log.info("Inserted running task log: id={}, taskId={}", taskLog.id, task.id)

        // Register in runningTasks map for stop capability
        runningTasks[taskLog.id] = SchedulerService.RunningTaskInfo(
            logId = taskLog.id,
            taskId = task.id,
            sessionId = sessionId,
        )

        try {
            val response = routerClient.chat(sessionId, task.prompt)

            taskLog.response = response.content
            taskLog.tokenUsage = response.tokenUsage?.toString() ?: ""

            // Check if this task was stopped during execution.
            // After the interrupt fix, call() returns a clean empty response instead of
            // throwing, so we need to check stoppedLogIds here (not just in the catch block).
            if (stoppedLogIds.remove(taskLog.id)) {
                taskLog.status = 5 // stopped by user (final)
                taskLog.errorInfo = "Task stopped by user"
                log.info("Task was stopped during execution: id={}, name={}", task.id, task.name)
            } else {
                taskLog.status = 1 // success
                log.info("Task execution succeeded: id={}, name={}", task.id, task.name)
            }
        } catch (e: Exception) {
            log.error("Task execution failed: id={}, name={}, error={}", task.id, task.name, e.message, e)
            // Check if this task was explicitly stopped by user
            if (stoppedLogIds.remove(taskLog.id)) {
                taskLog.status = 5 // stopped by user (final)
                taskLog.errorInfo = "Task stopped by user"
            } else {
                taskLog.status = 0 // failed
                taskLog.errorInfo = e.message?.take(4000) ?: "Unknown error"
            }
        } finally {
            // Clean up stoppedLogIds regardless of outcome to prevent memory leak
            stoppedLogIds.remove(taskLog.id)
            // NOTE: Do NOT remove from runningTasks here!
            // Keep it in the map until AFTER updateById so that:
            // - stopTask() finds it in runningTasks → INTERRUPT + stoppedLogIds → correct status
            // - stopTaskViaDb() sees the correct DB status after updateById
            // Removing too early causes a race: stopTaskViaDb sees stale status=3/4 → overwrites to 5

            // Compute end time and duration BEFORE any slow I/O (clearSession can take 10+ seconds)
            val endTime = LocalDateTime.now()
            taskLog.endTime = endTime
            taskLog.durationMs = if (taskLog.startTime != null) {
                Duration.between(taskLog.startTime, endTime).toMillis()
            } else {
                0
            }

            // Update DB IMMEDIATELY so the frontend sees the final status without waiting
            // for clearSession. updateById uses WHERE status IN (3, 4), so it can transition
            // from running (3) or stopping (4) to the final status (0/1/5).
            try {
                agentTaskLogMapper.updateById(taskLog)
                log.info("Updated agent task log: id={}, status={}, durationMs={}", taskLog.id, taskLog.status, taskLog.durationMs)
            } catch (e: Exception) {
                log.error("Failed to update task log: id={}, error={}", taskLog.id, e.message, e)
            }

            // NOW safe to remove from runningTasks — DB is already in final state.
            // Any concurrent stopTask() will either:
            // a) find it in runningTasks (before this line) → INTERRUPT + stoppedLogIds → correct
            // b) not find it (after this line) → stopTaskViaDb → sees correct DB status → correct
            runningTasks.remove(taskLog.id)

            // clearSession is slow (snapshot upload + container destroy) but non-critical for
            // status reporting. Run it after the DB update to avoid blocking status visibility.
            try {
                routerClient.clearSession(sessionId)
            } catch (e: Exception) {
                log.warn("Failed to clear session {}: {}", sessionId, e.message)
            }

            executionGuard.updateExecutionStatus(
                task.id,
                triggerTime,
                taskLog.status == 1,
                taskLog.startTime ?: endTime,
                endTime,
            )
        }
    }

    override fun stopTask(logId: Long): Boolean {
        val runningTask = runningTasks[logId]
        if (runningTask == null) {
            log.warn("No running task found for logId={}", logId)
            // Try to update the DB log directly in case the task is running on another instance
            return stopTaskViaDb(logId)
        }

        log.info("Stopping running task: logId={}, taskId={}, sessionId={}", logId, runningTask.taskId, runningTask.sessionId)

        // Mark this log as stopped so the catch block in executeTaskOnce() can detect it
        stoppedLogIds.add(logId)

        // Immediately update DB to status=4 (stopping) so the frontend gets instant feedback.
        // The final transition 4→5 (stopped) happens in executeTaskOnce()'s finally block.
        try {
            val rows = agentTaskLogMapper.updateStatusById(logId, 4, "Stopping...")
            log.info("Set task log status to stopping(4): logId={}, rowsAffected={}", logId, rows)
        } catch (e: Exception) {
            log.warn("Failed to set stopping status for logId={}: {}", logId, e.message)
        }

        // Send INTERRUPT command to router → agent-service → harnessAgent.interrupt()
        try {
            routerClient.sendCommand(runningTask.sessionId, CommandType.INTERRUPT)
        } catch (e: Exception) {
            log.warn("Failed to send INTERRUPT command for session={}: {}", runningTask.sessionId, e.message)
        }

        return true
    }

    /**
     * Fallback: when the task is not running on this instance, mark the DB log as stopped.
     * Used for cluster deployments where the task may be running on another scheduler instance.
     */
    private fun stopTaskViaDb(logId: Long): Boolean {
        val taskLog = agentTaskLogMapper.selectById(logId) ?: return false
        if (taskLog.status != 3 && taskLog.status != 4) return false // not running or stopping

        // Try to interrupt via Quartz if it's a scheduled job
        try {
            val jobKey = JobKey("AgentTask_${taskLog.taskId}", "AgentTaskGroup")
            if (scheduler.checkExists(jobKey)) {
                scheduler.interrupt(jobKey)
            }
        } catch (e: Exception) {
            log.warn("Failed to interrupt Quartz job for taskId={}: {}", taskLog.taskId, e.message)
        }

        // Also try to interrupt via sessionId if available
        val sessionId = taskLog.sessionId
        if (!sessionId.isNullOrBlank()) {
            try {
                routerClient.sendCommand(sessionId, CommandType.INTERRUPT)
                routerClient.clearSession(sessionId)
            } catch (e: Exception) {
                log.warn("Failed to send INTERRUPT to router for session={}: {}", sessionId, e.message)
            }
        }

        // Mark as stopped in DB (final status=5)
        taskLog.status = 5
        taskLog.endTime = LocalDateTime.now()
        taskLog.errorInfo = "Task stopped by user"
        taskLog.durationMs = if (taskLog.startTime != null) {
            Duration.between(taskLog.startTime, taskLog.endTime).toMillis()
        } else {
            0
        }
        agentTaskLogMapper.updateById(taskLog)
        return true
    }

    override fun getScheduledTaskIds(): Set<Long> {
        val jobKeys = scheduler.getJobKeys(org.quartz.impl.matchers.GroupMatcher.jobGroupEquals("AgentTaskGroup"))
        return jobKeys.mapNotNull { key ->
            key.name.removePrefix("AgentTask_").toLongOrNull()
        }.toSet()
    }

    /**
     * Check if a task has an actively running log (status=3 or 4) that is NOT stale.
     * Stale logs (exceeded timeout) are automatically marked as timeout (status=2).
     */
    private fun hasActiveRunningLog(task: AgentTask): Boolean {
        val runningLogs = agentTaskLogMapper.selectRunningByTaskId(task.id)
        if (runningLogs.isEmpty()) return false

        val now = LocalDateTime.now()
        val timeoutSeconds = if (task.timeoutSeconds > 0) task.timeoutSeconds.toLong() else DEFAULT_TIMEOUT_SECONDS
        var hasActive = false

        for (log in runningLogs) {
            val startTime = log.startTime ?: log.createTime
            val elapsed = Duration.between(startTime, now).seconds
            if (elapsed > timeoutSeconds) {
                // Stale log from previous crash/restart — mark as timeout
                log.status = 2 // timeout
                log.endTime = now
                log.errorInfo = "Auto-expired: no completion within ${timeoutSeconds}s (likely service restart)"
                log.durationMs = Duration.between(startTime, now).toMillis()
                agentTaskLogMapper.updateById(log)
                this.log.info("Auto-expired stale running log: logId={}, taskId={}, elapsed={}s", log.id, task.id, elapsed)
            } else {
                hasActive = true
            }
        }
        return hasActive
    }

    /**
     * Clean up stale running logs for ALL tasks on startup.
     * Called from [loadTasksToScheduler] to recover from previous crashes/restarts.
     */
    private fun cleanupAllStaleRunningLogs() {
        try {
            val runningLogs = agentTaskLogMapper.selectAllRunningLogs()
            if (runningLogs.isEmpty()) return

            val now = LocalDateTime.now()
            var cleanedCount = 0

            // Cache task timeout lookups
            val taskTimeoutCache = mutableMapOf<Long, Long>()

            for (taskLog in runningLogs) {
                val timeoutSeconds = taskTimeoutCache.getOrPut(taskLog.taskId) {
                    val task = agentTaskMapper.selectById(taskLog.taskId)
                    (if (task != null && task.timeoutSeconds > 0) task.timeoutSeconds else DEFAULT_TIMEOUT_SECONDS.toInt()).toLong()
                }

                val startTime = taskLog.startTime ?: taskLog.createTime
                val elapsed = Duration.between(startTime, now).seconds
                if (elapsed > timeoutSeconds) {
                    taskLog.status = 2 // timeout
                    taskLog.endTime = now
                    taskLog.errorInfo = "Auto-expired on service restart: no completion within ${timeoutSeconds}s"
                    taskLog.durationMs = Duration.between(startTime, now).toMillis()
                    agentTaskLogMapper.updateById(taskLog)
                    cleanedCount++
                }
            }

            if (cleanedCount > 0) {
                log.info("Cleaned up {} stale running task logs on startup", cleanedCount)
            }
        } catch (e: Exception) {
            log.warn("Failed to cleanup stale running logs on startup: {}", e.message)
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 300L
    }
}
