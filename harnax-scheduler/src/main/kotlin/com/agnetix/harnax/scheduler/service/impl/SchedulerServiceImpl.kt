package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.CommandDelivery
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.SchedulerHousekeepingJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
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
    private val jobInventory: QuartzJobInventory,
    /** The single writer of agent tasks into the Quartz store; see [TaskQuartzRegistrar]. */
    private val registrar: TaskQuartzRegistrar,
    /**
     * The same key [RouterClient] builds its `chat` read timeout from, on purpose: how long an execution
     * may take and how long until a row without one counts as a zombie have to be one number, or the
     * sweep expires work that is merely slow.
     */
    @Value("\${scheduler.timeout-seconds:300}") private val executionTimeoutSeconds: Int,
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

    /**
     * When this process last ran a stale-execution sweep to completion; null means never (or only
     * failures). Backs the rate limit in [expireStaleExecutionsThrottled]. Monotonic on purpose — a
     * wall-clock step from an NTP correction would either freeze the window or open it early.
     * `internal` only so the tests can move the clock back instead of waiting 30 seconds for a green run.
     */
    @Volatile
    internal var lastStaleSweepAtNanos: Long? = null

    @PostConstruct
    fun init() {
        // Register beans in scheduler context so Quartz jobs can access them.
        //
        // Deliberately outside the `scheduler.enabled` gate: the housekeeping sweep is registered on every
        // node (see onApplicationReady) and takes its collaborators out of *this* context, so gating the
        // registration too would register a job that fires and quietly does nothing — which is precisely
        // the hole that left a stopped execution's row at status 4 forever on an inert node.
        //
        // These are references, not work — and the store is shared, so they are not decoration either: a
        // JDBC cluster hands a fire to whichever node claims it, which can be this one even when the job
        // in it was registered by another instance, and even on an instance started with
        // `scheduler.enabled=false` that loaded nothing of its own. That is why the enabled check lives in
        // the fire path (see `AbstractAgentTaskJob.run`) rather than being implied by who registered the
        // job, and why the task itself is re-read from `agent_task` there instead of being carried in.
        val schedulerContext = scheduler.context
        schedulerContext["schedulerService"] = this
        schedulerContext["executionGuard"] = executionGuard
        schedulerContext["agentTaskMapper"] = agentTaskMapper

        if (!schedulerEnabled) {
            log.info("Scheduler is disabled on this instance: no task load, zombie reclaim still runs")
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        // Sweeping is not scheduling. It touches no user task, it lives in its own Quartz group, and it is
        // the only reclaim path for a row this node can still write: `stopTask` is deliberately open on a
        // disabled node (it writes no Quartz object), so without this the 4 it leaves behind would be
        // unrecoverable here — no load, no fire and no manual run ever calls expireStale on this instance.
        registerHousekeepingJob()
        if (!schedulerEnabled) {
            return
        }
        loadExecutor.execute { loadTasksWithRetry() }
    }

    /**
     * The sweeps have no other caller: until this registration existed, a guard row could only ever be
     * added and an execution log row never removed at all. Registered before the task load and on the
     * main thread, because none of it reads the database — the *sweeps* do, five minutes later, by which
     * time the retry loop may well have brought the database up.
     *
     * A repeating trigger rather than a cron: the sweep has no relationship to any user's schedule, and a
     * cron would make it miss while this instance was down for the very restart that leaves zombies.
     */
    private fun registerHousekeepingJob() {
        try {
            val jobKey = JobKey(SchedulerHousekeepingJob.JOB_NAME, SchedulerHousekeepingJob.GROUP)
            if (scheduler.checkExists(jobKey)) {
                log.info("Housekeeping job is already registered, leaving it alone")
                return
            }
            val jobDetail = JobBuilder.newJob(SchedulerHousekeepingJob::class.java)
                .withIdentity(jobKey)
                .storeDurably()
                .build()
            val trigger = TriggerBuilder.newTrigger()
                .withIdentity(TriggerKey(SchedulerHousekeepingJob.JOB_NAME, SchedulerHousekeepingJob.GROUP))
                .forJob(jobKey)
                .startNow()
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMinutes(5).repeatForever())
                .build()
            scheduler.scheduleJob(jobDetail, trigger)
            log.info("Registered scheduler housekeeping job (every 5 minutes)")
        } catch (e: Exception) {
            // Loud but fatal is the wrong way round here: a node that cannot sweep is degraded, and the
            // task load that follows is the one that decides whether it schedules anything at all.
            log.warn("Housekeeping job could not be registered: {}", e.message)
        }
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

    /**
     * Registering is the registrar's job alone now: it owns the job/trigger identity, the cron and the
     * misfire rules, and — the part that matters on a JDBC store — the JobDataMap, which carries only the
     * task id so a fire reads the *current* row rather than a snapshot taken when this call last ran.
     */
    override fun scheduleTask(task: AgentTask) = registrar.register(task)

    override fun unscheduleTask(task: AgentTask) = registrar.unregister(task.id)

    override fun loadTasksToScheduler(): Boolean {
        log.info("Loading agent tasks to scheduler")

        // Step 0: Clean up stale running logs from previous crashes/restarts
        expireStaleExecutions()

        // Step 1: Clean up all existing Quartz jobs in AgentTaskGroup
        try {
            val existingKeys =
                scheduler.getJobKeys(org.quartz.impl.matchers.GroupMatcher.jobGroupEquals(TaskQuartzRegistrar.GROUP_AGENT_TASK))
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
        // Same contract as the cron registration: this store cannot hold an AgentTask, and the job re-reads
        // the row when it fires (`AbstractAgentTaskJob.taskToRun`).
        val jobDataMap = JobDataMap().apply { put(TaskQuartzRegistrar.KEY_TASK_ID, task.id.toString()) }
        val jobDetail = JobBuilder.newJob(registrar.jobClassFor(task))
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
        // overlap. A zombie left by a dead node cannot block the trigger forever either — the read
        // reclaims it, rate limited per node (hasActiveRunningExecution), and housekeeping sweeps every
        // five minutes regardless.
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
        //
        // Quartz knows nothing about this thread, and that is a promise the reader has to be told:
        // `waitForJobsToCompleteOnShutdown` waits for worker threads, so neither it nor the container's
        // stop_grace_period covers a manual run — a restart while this is in flight leaves agent_task_log
        // at 3 and its lock row at 0 for housekeeping to reap. Merging one-shot runs into Quartz (S4) is
        // what would put this path under the same protection as the cron one.
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
            // Compute end time and duration BEFORE any slow I/O: clearSession is the tail of the
            // execution, and its own capped read timeout (scheduler.clear-session-timeout-seconds,
            // 60s by default) is what makes the shutdown budget below a number rather than a hope.
            val endTime = LocalDateTime.now()
            taskLog.endTime = endTime
            taskLog.durationMs = if (taskLog.startTime != null) {
                Duration.between(taskLog.startTime, endTime).toMillis()
            } else {
                0
            }

            // Write the final status immediately so the frontend sees it without waiting for
            // clearSession. finishExecution only matches while the row is still running (3); zero
            // rows means someone else moved it, and *where* they moved it decides what is true now.
            try {
                if (agentTaskLogMapper.finishExecution(taskLog) > 0) {
                    log.info("Updated agent task log: id={}, status={}, durationMs={}", taskLog.id, taskLog.status, taskLog.durationMs)
                } else {
                    closeOutLateExecution(taskLog)
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
     * The row left status 3 while this execution was still in flight. Re-read it instead of assuming
     * a user stop: a row the reaper had judged `timeout` is a run that finished successfully but
     * would otherwise be remembered as a failure, and that mistake is ours to correct because we hold
     * the real result.
     *
     * **Two orderings, one of which this method cannot see.** A stop lives only as `status = 4`, and the
     * stale sweep also writes that row (4 -> 2), so the outcome depends on where the sweep lands relative
     * to the re-read below:
     * - sweep lands *after* the read of 4 → the 4 branch, whose guarded UPDATE misses, recovers via
     *   `reclaimExpired` and still writes 5. Protected.
     * - sweep lands *before* the read → the 2 branch, which cannot tell a stopped-then-reaped row from a
     *   merely-reaped one: `expireStale` has already overwritten `error_info`, so the 4 that existed left
     *   no trace. It writes this thread's real 0/1 and the user's stop disappears from the record — no
     *   longer mislabelled "timeout", but still the wrong row.
     * The second one is a known, accepted gap: closing it needs the stop intent carried by something
     * other than a transient status (see spec `2026-09-11-scheduler-cluster-design.md` F11, S2/S3 scope).
     */
    private fun closeOutLateExecution(taskLog: AgentTaskLog) {
        when (agentTaskLogMapper.selectById(taskLog.id)?.status) {
            4 -> {
                taskLog.status = 5 // stopped by user (final)
                taskLog.errorInfo = "Task stopped by user"
                val closed = agentTaskLogMapper.finalizeStopped(taskLog)
                if (closed > 0) {
                    log.info("Task was stopped during execution: id={}", taskLog.id)
                } else if (agentTaskLogMapper.selectById(taskLog.id)?.status == 2) {
                    // The window this closes: read 4, then the stale sweep reaped it to 2 *before* our
                    // 4-guarded UPDATE ran, so that UPDATE matched nothing. Left alone, a run the user
                    // really stopped is remembered as a timeout — the exact symptom this whole series
                    // exists to remove. reclaimExpired is the recovery the 2 branch below already uses:
                    // it still guards on status = 2, so the only row it can touch is one the reaper had
                    // guessed about, and the verdict it writes stays this thread's own (5, from the stop
                    // it observed). A sweep that got here *before* the read above never reaches this
                    // branch — see the two-orderings note on the method: that one is the open gap.
                    if (agentTaskLogMapper.reclaimExpired(taskLog) > 0) {
                        log.info(
                            "Task {} was reaped as a timeout after the stop was read; wrote stopped over it",
                            taskLog.id,
                        )
                    } else {
                        log.warn("Execution log {} moved again mid-stop; result not written", taskLog.id)
                    }
                } else {
                    log.warn("Execution log {} changed again mid-stop; result not written", taskLog.id)
                }
            }

            2 -> {
                // reclaimExpired guards the row it replaces (status 2) but not the status it is handed,
                // so the value stays this thread's own verdict: 0 or 1. Anything else would write a
                // result nobody produced over a row the reaper already gave up on.
                //
                // This is also where a stop that the sweep reaped *before* the read above lands: such a
                // row is indistinguishable from one nobody ever stopped (`expireStale` overwrote the
                // error_info that carried the 4), so writing 0/1 here is exactly what erases that stop
                // from the record. Deliberate — guessing a 5 from a row that reads 2 would fabricate a
                // user action. Closing it needs a stop marker on the row, not a smarter branch (F11).
                check(taskLog.status == 0 || taskLog.status == 1) {
                    "Refusing to reclaim log ${taskLog.id} with status ${taskLog.status}"
                }
                if (agentTaskLogMapper.reclaimExpired(taskLog) > 0) {
                    log.info(
                        "Execution log {} had been auto-expired; wrote the real result (status={}) over it",
                        taskLog.id,
                        taskLog.status,
                    )
                } else {
                    log.warn("Execution log {} moved again before it could be reclaimed", taskLog.id)
                }
            }

            else -> log.warn(
                "Execution log {} was already terminal elsewhere; its real result (status={}) was not written",
                taskLog.id,
                taskLog.status,
            )
        }
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
            when (val delivery = routerClient.sendCommand(sessionId, CommandType.INTERRUPT)) {
                CommandDelivery.Delivered ->
                    log.info("Interrupt reached the execution behind session {}; it closes the row", sessionId)

                is CommandDelivery.Missed -> {
                    // An instance answered that nothing is running for this session, so no node will ever
                    // report this row's outcome and the stop is final here. Leaving it at 4 would let the
                    // reaper label a finished run "timeout".
                    val now = LocalDateTime.now()
                    taskLog.status = 5
                    taskLog.errorInfo = "No live execution to interrupt"
                    taskLog.endTime = now
                    taskLog.durationMs = Duration.between(taskLog.startTime ?: taskLog.createTime ?: now, now).toMillis()
                    if (agentTaskLogMapper.finalizeStopped(taskLog) == 0) {
                        // The row is no longer at 4, so nothing here wrote an outcome — and the caller
                        // has been answered "stopped". This branch exists to keep the reaper from having
                        // to guess about this execution; a silently-matched 0 rows puts the guess back.
                        log.error(
                            "Task log {} was reported as having no live execution but its 4 -> 5 close-out " +
                                "matched no row; its outcome was not written by this node",
                            logId,
                        )
                    } else {
                        log.info(
                            "Task log {} closed as stopped: the agent reported {} (session={})",
                            logId,
                            delivery.message ?: "no live execution",
                            sessionId,
                        )
                    }
                }

                is CommandDelivery.Unanswered -> log.warn(
                    "Task log {} stays at stopping: the command never got through ({}), so nothing is known " +
                        "about the execution — its own node writes the outcome, or the stale sweep times it out",
                    logId,
                    delivery.reason,
                )
            }
        }
        return true
    }

    /**
     * Kept on the interface for `SchedulerController`'s status endpoint; the read itself belongs to
     * [QuartzJobInventory] so the health indicator and the gauge can use the same implementation without
     * depending on this service.
     */
    override fun getScheduledTaskIds(): Set<Long> = jobInventory.scheduledTaskIds()

    /**
     * Whether a manual run has to wait for an execution that is already in flight.
     *
     * `concurrent` is the task's own answer to that question (entity/DDL: 0 = no overlap, 1 = allow),
     * so refusing a "run now" on a concurrent=1 task rejected a conflict the task explicitly permits.
     * It is read *first* for the same reason: for a task that permits overlap the store is not consulted
     * at all, so a concurrent=1 "run now" no longer costs a liveness read — and no sweep.
     */
    private fun blocksManualRun(task: AgentTask): Boolean = task.concurrent == 0 && hasActiveRunningExecution(task.id)

    /**
     * Whether the task has a live execution.
     *
     * The per-task read answers first and, when it comes back empty, answers alone: a task with no row at
     * 3/4 has nothing to judge, and sweeping the whole log table on that answer is what put a scan-type
     * UPDATE in front of every single fire — see [expireStaleExecutionsThrottled]. Only a row that this
     * read did see is worth judging against its own timeout, so the reclaim runs when there is something
     * to reclaim, and never more than once per [MIN_STALE_SWEEP_INTERVAL_MS] per process.
     *
     * Consequence worth knowing: a row that went stale inside that window makes a manual run get refused
     * for up to 30 extra seconds. The window exists because the alternative is worse — a fire whose sweep
     * loses a lock fight to a concurrent insert does not run at all.
     *
     * Two callers, for two different holes: the manual paths above, and a Quartz fire asking before it
     * starts work — `@DisallowConcurrentExecution` only mutualises one JobDetail, and one task owns
     * several (its cron job plus every one-shot), so the annotation cannot see across them. This read
     * is keyed by task and can.
     */
    override fun hasActiveRunningExecution(taskId: Long): Boolean {
        if (agentTaskLogMapper.selectRunningByTaskId(taskId).isEmpty()) {
            return false
        }
        expireStaleExecutionsThrottled()
        return agentTaskLogMapper.selectRunningByTaskId(taskId).isNotEmpty()
    }

    /**
     * What the fire path checks before it runs anything: with a shared store a job can reach a node that
     * registered nothing, so `scheduler.enabled` has to be answered at fire time rather than at load time.
     */
    override val schedulingEnabled: Boolean
        get() = schedulerEnabled

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

    /**
     * Reclaim executions that outran their own timeout, judged per row in SQL.
     *
     * This is a *scan-type* UPDATE over every row at 3/4, i.e. over the whole live end of `idx_status`,
     * and the runs that are starting at the same moment insert into exactly that range. On MySQL the two
     * fight over next-key/gap locks, and whichever one it rolls back loses real work: roll back the
     * insert and that Quartz fire does not execute at all, with only an error line to show for it. The
     * sweep therefore has no business running once per fire — see [expireStaleExecutionsThrottled].
     *
     * Three kinds of caller reach this: the startup load and the housekeeping sweep (both unthrottled,
     * and both of which *count* as this process's last sweep), and a fire asking whether a row it can see
     * is still live. A failure is a log line plus 0: the sweep must not be the thing that takes its
     * caller down — and it does not count as a sweep, so the next caller may try the statement again
     * immediately. Stamping the window on the way *in* let one dead connection silence this node's
     * retries for the full [MIN_STALE_SWEEP_INTERVAL_MS], which is the opposite of what a failed
     * reclaim needs.
     */
    override fun expireStaleExecutions(): Int {
        val expired = try {
            agentTaskLogMapper.expireStale(executionTimeoutSeconds)
        } catch (e: Exception) {
            log.warn("Failed to expire stale running task logs: {}", e.message)
            return 0
        }
        // Only a statement that actually ran earns the window.
        lastStaleSweepAtNanos = System.nanoTime()
        if (expired > 0) {
            log.info("Expired {} stale running task log(s) (baseline {}s)", expired, executionTimeoutSeconds)
        }
        return expired
    }

    /**
     * [expireStaleExecutions] behind a process-local rate limit: at most one sweep per
     * [MIN_STALE_SWEEP_INTERVAL_MS] here, whatever number of fires asks.
     *
     * Nothing is lost by waiting. Global reclaim is housekeeping's job, and since G3 it runs on every
     * node including disabled ones, five minutes apart; this call site only ever asks "is the row I can
     * see still live", and the answer is the same one a *successful* sweep gave at most 30s ago — a row
     * that crossed its own 1.5x deadline inside that window was not stale when the last sweep looked at
     * it. One that threw is not in that count (see [expireStaleExecutions]).
     *
     * A plain `@Volatile` timestamp rather than a lock: two fires that arrive in the same millisecond can
     * both pass this check and both sweep, which costs one extra statement per node per window at worst.
     * Making it exact would mean serialising fires behind the very statement that is being rationed.
     */
    private fun expireStaleExecutionsThrottled(): Int {
        val last = lastStaleSweepAtNanos
        if (last != null) {
            val sinceMs = (System.nanoTime() - last) / 1_000_000
            if (sinceMs < MIN_STALE_SWEEP_INTERVAL_MS) {
                log.debug("Skipping the stale sweep: one already ran {}ms ago", sinceMs)
                return 0
            }
        }
        return expireStaleExecutions()
    }

    /**
     * The log table's only removal path: every run appends a row holding the prompt and the whole agent
     * response, and nothing had ever taken one away.
     */
    override fun cleanupOldExecutionLogs(retentionDays: Int): Int = try {
        val deleted = agentTaskLogMapper.deleteOldLogs(LocalDateTime.now().minusDays(retentionDays.toLong()))
        if (deleted > 0) {
            log.info("Removed {} execution log(s) older than {} days", deleted, retentionDays)
        }
        deleted
    } catch (e: Exception) {
        log.warn("Failed to apply the execution-log retention: {}", e.message)
        0
    }

    companion object {
        private const val INITIAL_RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
        private const val ALERT_AFTER_ATTEMPTS = 5

        /** Upper bound for the ids listed in a load-drift error, which shows up in /actuator/health */
        private const val MAX_DRIFT_IDS = 20

        /**
         * Floor between two stale-execution sweeps on this node. Deliberately far below housekeeping's
         * 5-minute round — so the throttle never suppresses the reclaim that is supposed to happen — and
         * far above the burst of statements one task can produce (a fire, its manual run, a retry), which
         * is what used to multiply the scan-type UPDATE.
         */
        private const val MIN_STALE_SWEEP_INTERVAL_MS = 30_000L
    }
}
