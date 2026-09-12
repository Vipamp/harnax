package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.AgentTaskJob
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.quartz.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executors

@Service
class SchedulerServiceImpl(
    private val schedulerFactory: SchedulerFactoryBean,
    private val agentTaskMapper: AgentTaskMapper,
    private val agentTaskLogMapper: AgentTaskLogMapper,
    private val routerClient: RouterClient,
    private val executionGuard: AgentTaskExecutionGuard,
    private val status: SchedulerStatus,
    private val metrics: SchedulerMetrics,
    @Value("\${scheduler.enabled:true}") private val schedulerEnabled: Boolean,
) : SchedulerService {

    private val log = LoggerFactory.getLogger(SchedulerServiceImpl::class.java)

    private val scheduler: Scheduler
        get() = schedulerFactory.scheduler

    /**
     * Startup load runs off the main thread: the retry loop below can legitimately sit in backoff
     * for minutes while the database comes back, and ApplicationReadyEvent listeners run inline.
     */
    private val loadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scheduler-initial-load").apply { isDaemon = true }
    }

    @Volatile
    private var shuttingDown = false

    @PostConstruct
    fun init() {
        if (!schedulerEnabled) {
            log.info("Scheduler is disabled on this instance")
            return
        }

        // Register beans in scheduler context so Quartz jobs can access them
        val schedulerContext = scheduler.context
        schedulerContext["schedulerService"] = this
        schedulerContext["executionGuard"] = executionGuard
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (!schedulerEnabled) {
            return
        }
        loadExecutor.execute { loadTasksWithRetry() }
    }

    @PreDestroy
    fun shutdownLoadExecutor() {
        shuttingDown = true
        loadExecutor.shutdownNow()
    }

    /**
     * Retry the initial load until it registers every active task: one attempt at startup used to
     * leave this instance scheduling nothing whenever MySQL was slower than the JVM. Failures go
     * through [SchedulerStatus] so /actuator/health reports DOWN instead of hiding it.
     */
    private fun loadTasksWithRetry() {
        var attempt = 0
        var delayMs = INITIAL_RETRY_DELAY_MS
        while (!shuttingDown) {
            attempt++
            val failure: String? = try {
                if (loadTasksToScheduler()) {
                    if (attempt > 1) {
                        log.info("Loaded agent tasks to scheduler after {} attempts", attempt)
                    }
                    null
                } else {
                    status.lastLoadError ?: "load did not register every active task"
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                status.recordLoadFailure(reason)
                metrics.recordLoadAttempt(success = false)
                log.warn("Agent task load attempt {} threw", attempt, e)
                reason
            }
            if (failure == null) {
                return
            }
            if (attempt == ALERT_AFTER_ATTEMPTS) {
                log.error("Scheduler is still not scheduling anything after {} attempts: {}", attempt, failure)
            }
            delayMs = sleepBeforeRetry(delayMs) ?: return
        }
    }

    /** @return the next backoff, or null when the thread was interrupted and the loop must stop */
    private fun sleepBeforeRetry(delayMs: Long): Long? = try {
        Thread.sleep(delayMs)
        (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
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

    override fun loadTasksToScheduler(): Boolean {
        log.info("Loading agent tasks to scheduler")

        // Step 0: Clean up stale running logs from previous crashes/restarts
        expireStaleExecutions()

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

        // Step 2: Schedule all active tasks from DB. A failure here must reach the caller: this is
        // the read that decides whether the instance schedules anything at all.
        val activeTasks = agentTaskMapper.selectRunningTasks()
        log.info("Found {} running agent tasks", activeTasks.size)

        var scheduled = 0
        val failedIds = mutableListOf<Long>()
        for (task in activeTasks) {
            try {
                scheduleTask(task)
                scheduled++
                log.info("Loaded agent task to scheduler: id={}, name={}", task.id, task.name)
            } catch (e: Exception) {
                failedIds += task.id
                log.error("Failed to load agent task: id={}, name={}, error={}", task.id, task.name, e.message, e)
            }
        }

        // A load that registered nothing but should have is the exact state this status exists to
        // surface; reporting success here would put the health check back to a lie.
        if (activeTasks.isNotEmpty() && scheduled == 0) {
            status.recordLoadFailure("none of the ${activeTasks.size} active tasks could be registered")
            metrics.recordLoadAttempt(success = false)
            return false
        }

        // Registering *some* of them is not a success either: the failed tasks simply never fire on
        // this instance. The drift has to stay in lastLoadError (with the ids, which are what an
        // operator can act on) so the health check keeps saying DOWN and /reload keeps saying no.
        val drift = describeDrift(failedIds, activeTasks.size)
        status.recordLoadSuccess(scheduled, drift)
        metrics.recordLoadAttempt(success = drift == null)
        return drift == null
    }

    override fun startTask(id: Long): Boolean {
        val task = agentTaskMapper.selectAnyById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        // Always re-schedule and update DB, even if already marked as running
        // (handles recovery from previous broken toggles)
        scheduleTask(task)
        return agentTaskMapper.updateStatus(id, 1) > 0
    }

    override fun pauseTask(id: Long): Boolean {
        val task = agentTaskMapper.selectAnyById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        // Always delete from Quartz and update DB (handles recovery from broken toggles)
        unscheduleTask(task)
        return agentTaskMapper.updateStatus(id, 0) > 0
    }

    override fun runTaskOnce(id: Long): Boolean {
        val task = agentTaskMapper.selectAnyById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        // Guard: an in-flight execution only blocks a task that forbids overlap (blocksManualRun).
        if (blocksManualRun(task)) {
            log.warn("Task {} has an active running execution and allows no overlap, rejecting runOnce", task.id)
            return false
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
        val task = agentTaskMapper.selectAnyById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        val triggerTime = LocalDateTime.now()

        // Guard: an execution still in flight only counts as a conflict for a task that forbids
        // overlap. Stale rows are expired on the way, so a zombie cannot block the trigger forever.
        if (blocksManualRun(task)) {
            log.warn("Task {} has an active running execution and allows no overlap, rejecting trigger", task.id)
            return false
        }

        // Multi-instance guard (synchronous check)
        if (!executionGuard.tryAcquireLock(task.id, triggerTime)) {
            log.info("Task {} already being executed by another instance, skipping", task.id)
            return false
        }

        log.info("Manually triggering agent task: id={}, name={}", task.id, task.name)

        // Execute asynchronously via separate thread (Spring @Async doesn't work on self-invocation).
        // Same shape as AgentTaskJob: an exception escaping here would die with the thread — the caller
        // has already been answered "Task triggered", so the log line is the only trace left.
        Thread {
            try {
                executeTaskOnce(task, triggerTime)
            } catch (e: Exception) {
                log.error("Manual task execution failed: id={}, name={}, error={}", task.id, task.name, e.message, e)
            }
        }.apply {
            name = "manual-trigger-${task.id}"
            isDaemon = true
            start()
        }

        return true
    }

    /**
     * Shared task execution logic used by both Quartz jobs and manual triggers.
     * Inserts the running log row, calls the router, then closes the row out through a
     * status-guarded update.
     */
    override fun executeTaskOnce(task: AgentTask, triggerTime: LocalDateTime) {
        val taskLog = insertRunningLog(task, triggerTime)
        val sessionId = taskLog.sessionId

        try {
            val response = routerClient.chat(sessionId, task.prompt)
            taskLog.response = response.content
            taskLog.tokenUsage = response.tokenUsage?.toString() ?: ""
            taskLog.status = 1 // success
            log.info("Task execution succeeded: id={}, name={}", task.id, task.name)
        } catch (e: Exception) {
            log.error("Task execution failed: id={}, name={}, error={}", task.id, task.name, e.message, e)
            taskLog.status = 0 // failed
            taskLog.errorInfo = e.message?.take(4000) ?: "Unknown error"
        } finally {
            // Compute end time and duration BEFORE any slow I/O (clearSession can take 10+ seconds)
            val endTime = LocalDateTime.now()
            taskLog.endTime = endTime
            taskLog.durationMs = if (taskLog.startTime != null) {
                Duration.between(taskLog.startTime, endTime).toMillis()
            } else {
                0
            }

            // Write the final status immediately so the frontend sees it without waiting for
            // clearSession. finishExecution only matches while the row is still running (3); zero
            // rows means a stop was requested mid-flight and the 4 -> 5 transition is ours.
            try {
                if (agentTaskLogMapper.finishExecution(taskLog) > 0) {
                    log.info("Updated agent task log: id={}, status={}, durationMs={}", taskLog.id, taskLog.status, taskLog.durationMs)
                } else {
                    taskLog.status = 5 // stopped by user (final)
                    taskLog.errorInfo = "Task stopped by user"
                    if (agentTaskLogMapper.finalizeStopped(taskLog) > 0) {
                        log.info("Task was stopped during execution: id={}, name={}", task.id, task.name)
                    } else {
                        log.warn("Execution log {} was already finalised elsewhere: taskId={}", taskLog.id, task.id)
                    }
                }
            } catch (e: Exception) {
                // Left at 3 or 4 on purpose: expireStale reclaims it as a timeout rather than this
                // node guessing at a status it could not persist.
                log.error("Failed to update task log: id={}, error={}", taskLog.id, e.message, e)
            }

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

    /**
     * Create the running row an execution is reported through, or fail before it starts.
     *
     * The insert sits outside [executeTaskOnce]'s try/finally on purpose — nothing may reach the router
     * without a row to write the outcome into — which also made it the one step whose failure nobody
     * handled: the affected-row count was dropped, so a rejected insert (column overflow, constraint,
     * dead connection) left no log row, a cluster lock stuck at status=0 that every later trigger reads
     * as "already running", and a caller that had already been answered "Task triggered".
     *
     * There is no row left to close out here, so releasing the lock in the catch is the only write
     * still available; [executionGuard]'s lookup is keyed by task id + trigger time, not by log id.
     * Null-lock-row cleanup is housekeeping's job (T4/T9); this only refuses to fake a success.
     */
    private fun insertRunningLog(
        task: AgentTask,
        triggerTime: LocalDateTime,
    ): AgentTaskLog {
        val taskLog = AgentTaskLog().apply {
            taskId = task.id
            taskName = task.name
            prompt = task.prompt
            startTime = LocalDateTime.now()
            creator = task.creator
            createTime = LocalDateTime.now()
            status = 3 // running
            sessionId = "task-${task.id}-${UUID.randomUUID()}"
        }
        val startedAt = taskLog.startTime ?: LocalDateTime.now()

        try {
            val inserted = agentTaskLogMapper.insert(taskLog)
            // MyBatis writes the generated key back on success; 0 rows or a missing id both mean the
            // row is not there, and an id of 0 would make every later update a no-op.
            if (inserted <= 0 || taskLog.id <= 0L) {
                throw IllegalStateException(
                    "Running log row for task ${task.id} was not created (affected rows=$inserted, id=${taskLog.id})",
                )
            }
            log.info("Inserted running task log: id={}, taskId={}", taskLog.id, task.id)
        } catch (e: Exception) {
            log.error("Task {} cannot start without its running log row: {}", task.id, e.message, e)
            executionGuard.updateExecutionStatus(task.id, triggerTime, false, startedAt, LocalDateTime.now())
            throw e
        }
        return taskLog
    }

    /**
     * Request a stop for one execution. Any node can service this: the log row carries the session
     * id, and status 4 is the signal the executing thread acts on when it writes its result back.
     */
    override fun stopTask(logId: Long): Boolean {
        val taskLog = agentTaskLogMapper.selectById(logId) ?: return false
        if (taskLog.status != 3 && taskLog.status != 4) {
            log.info("Task log {} is not running (status={}), nothing to stop", logId, taskLog.status)
            return false
        }

        // 3 -> 4 gives the frontend instant feedback; the executing thread closes 4 -> 5.
        // Matching 0 rows here is expected for a repeat stop on a row already at 4.
        val claimed = agentTaskLogMapper.markStopping(logId, "Stopping...") > 0
        if (!claimed && taskLog.status != 4) {
            log.info("Task log {} reached a final status before the stop could be claimed", logId)
            return false
        }

        val sessionId = taskLog.sessionId
        if (sessionId.isNullOrBlank()) {
            log.warn("Task log {} has no session id; the execution will only stop on its own", logId)
        } else {
            // Router -> agent -> harnessAgent.interrupt(). The session is NOT cleared here: the node
            // running the task owns that cleanup, and tearing it down from here would destroy a live run.
            routerClient.sendCommand(sessionId, CommandType.INTERRUPT)
        }
        return true
    }

    override fun getScheduledTaskIds(): Set<Long> {
        val jobKeys = scheduler.getJobKeys(org.quartz.impl.matchers.GroupMatcher.jobGroupEquals("AgentTaskGroup"))
        return jobKeys.mapNotNull { key ->
            key.name.removePrefix("AgentTask_").toLongOrNull()
        }.toSet()
    }

    /**
     * Whether a manual run has to wait for an execution that is already in flight.
     *
     * `concurrent` is the task's own answer to that question (entity/DDL: 0 = no overlap, 1 = allow),
     * so refusing a "run now" on a concurrent=1 task rejected a conflict the task explicitly permits.
     * The stale sweep still runs first and for both kinds: it is what stops a zombie left by a dead node
     * from counting as a live execution forever.
     */
    private fun blocksManualRun(task: AgentTask): Boolean = hasActiveRunningLog(task.id) && task.concurrent == 0

    /**
     * Whether the task has a live execution. Stale rows are expired first, so a zombie left behind by
     * a node that died mid-task cannot block the trigger forever.
     */
    private fun hasActiveRunningLog(taskId: Long): Boolean {
        expireStaleExecutions()
        return agentTaskLogMapper.selectRunningByTaskId(taskId).isNotEmpty()
    }

    /** Null means every active task was registered; the text doubles as the health detail. */
    private fun describeDrift(failedIds: List<Long>, total: Int): String? {
        if (failedIds.isEmpty()) {
            return null
        }
        // Bounded: a table full of unusable crons must not turn a health detail into a megabyte.
        val shown = failedIds.take(MAX_DRIFT_IDS).joinToString(",")
        val hidden = if (failedIds.size > MAX_DRIFT_IDS) ",+${failedIds.size - MAX_DRIFT_IDS} more" else ""
        val message = "${failedIds.size} of $total active tasks could not be registered: ids=[$shown$hidden]"
        log.error("Load left drift: {}", message)
        return message
    }

    /** Reclaim executions that outran their own timeout, judged per row in SQL. */
    private fun expireStaleExecutions() {
        try {
            val expired = agentTaskLogMapper.expireStale(DEFAULT_TIMEOUT_SECONDS)
            if (expired > 0) {
                log.info("Expired {} stale running task log(s)", expired)
            }
        } catch (e: Exception) {
            log.warn("Failed to expire stale running task logs: {}", e.message)
        }
    }

    companion object {
        /** Fallback for a task row without a usable timeout of its own */
        private const val DEFAULT_TIMEOUT_SECONDS = 300
        private const val INITIAL_RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
        private const val ALERT_AFTER_ATTEMPTS = 5

        /** Upper bound for the ids listed in a load-drift error, which shows up in /actuator/health */
        private const val MAX_DRIFT_IDS = 20
    }
}
