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
import com.agnetix.harnax.scheduler.job.SchedulerReconcileJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.ReconcileReport
import com.agnetix.harnax.scheduler.service.SchedulerService
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
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
    /** The single reader-and-writer of the *whole* group; the startup path below is one of its callers. */
    private val reconciler: TaskScheduleReconciler,
    /**
     * The same key [RouterClient] builds its `chat` read timeout from, on purpose: how long an execution
     * may take and how long until a row without one counts as a zombie have to be one number, or the
     * sweep expires work that is merely slow.
     */
    @Value("\${scheduler.timeout-seconds:300}") private val executionTimeoutSeconds: Int,
    /** How often the cluster re-checks the store against the table. 60s is the CRUD-loss window bound. */
    @Value("\${scheduler.reconcile-interval-seconds:60}") private val reconcileIntervalSeconds: Int,
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
     * Whether the store still owes one of the sweeps this node is responsible for registering. Set by the
     * boot-time attempt and cleared by [registerSweepsIfPending], which the converge loop keeps calling:
     * a registration that failed because the database was slower than the JVM used to be lost for the rest
     * of the process's life, and the sweeps it cost the cluster are what bound a lost CRUD notification.
     */
    @Volatile
    private var systemSweepsPending = false

    /**
     * Backoff base of the startup loop. `internal` only so the test that pins the five-attempt alert does
     * not have to sit through 2s + 4s + 8s + 16s of real backoff; nothing else moves it.
     */
    internal var initialRetryDelayMs = INITIAL_RETRY_DELAY_MS

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
        // Deliberately outside the `scheduler.enabled` gate, and the reason is no longer the one G3 left
        // behind. It is still true that the sweep is registered on every node (see onApplicationReady) and
        // takes its collaborators out of *this* context, so gating this too would register a job that fires
        // and quietly does nothing. What has changed is *whose* reclaim that job is: with a shared store the
        // sweep is a cluster singleton, so a row an inert node's own `stopTask` leaves at 4 is reaped by
        // whichever node in the cluster fires it. Reclamation continues while at least one node is enabled,
        // and a disabled node has no private sweep to lose.
        //
        // The other half of the reason stands on its own: these are references, not work — and the shared
        // store means they are not decoration either. A JDBC cluster hands a fire to whichever node claims
        // it, which can be this one even when the job in it was registered by another instance, and even on
        // an instance started with `scheduler.enabled=false` that loaded nothing of its own. That is why the
        // enabled check lives in the fire path (see `AbstractAgentTaskJob.run`) rather than being implied by
        // who registered the job, and why the task itself is re-read from `agent_task` there instead of
        // being carried in.
        val schedulerContext = scheduler.context
        schedulerContext["schedulerService"] = this
        schedulerContext["executionGuard"] = executionGuard
        schedulerContext["agentTaskMapper"] = agentTaskMapper
        schedulerContext["taskScheduleReconciler"] = reconciler

        if (!schedulerEnabled) {
            log.info("Scheduler is disabled on this instance: no task reconcile, zombie reclaim still runs")
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        // Both sweeps are attempted here, on the boot thread, before the converge loop's first database read:
        // that ordering is what lets a node which schedules nothing still get the sweeps *into the store*
        // (and see [registerHousekeepingJob] for why registering is legal on a standby node, and for what it
        // does not buy there). What boot's attempt could not do is fail twice — a
        // database still starting used to cost a WARN line and nothing more, and the cluster then ran without
        // its sweeps for the lifetime of the process. [registerSweepsIfPending] is the retry.
        systemSweepsPending = !registerSystemSweeps()
        if (!schedulerEnabled) {
            // A disabled node runs no converge loop, so it is the one node whose sweep *registration* would
            // still be lost for good; it owes itself the retry. The *reconcile* sweep stays off an inert node
            // either way (see [registerSystemSweeps]).
            if (systemSweepsPending) loadExecutor.execute { registerSweepsUntilStored() }
            return
        }
        loadExecutor.execute { loadTasksWithRetry() }
    }

    /**
     * The two periodic sweeps this node is responsible for putting in the store.
     *
     * @return false when the store refused either one, which is the state [registerSweepsIfPending] retries
     * out of the converge loop.
     */
    private fun registerSystemSweeps(): Boolean {
        val housekeeping = registerHousekeepingJob()
        // The reconcile sweep is registered inside the `schedulerEnabled` gate on purpose: a reconcile *is*
        // a scheduling write over the shared store, and with a JDBC store the sweep lands *in that store*,
        // so one enabled node registers it for the whole cluster. A disabled node registering it would only
        // add a second claim path to the same work.
        val reconcile = !schedulerEnabled || registerReconcileJob()
        return housekeeping && reconcile
    }

    /** No-op once boot's attempt got both sweeps into the store; see [systemSweepsPending]. */
    private fun registerSweepsIfPending() {
        if (systemSweepsPending) {
            systemSweepsPending = !registerSystemSweeps()
        }
    }

    /**
     * The retries a disabled node owes itself: it starts no converge loop, so nothing else would ever try
     * again. Same backoff as the rounds and the same exit on shutdown. What it owes is the housekeeping sweep
     * alone — [registerSystemSweeps] short-circuits the reconcile one on `schedulerEnabled`, so a retry from
     * an inert node cannot smuggle in the scheduling write it must not make.
     */
    private fun registerSweepsUntilStored() {
        var delayMs = initialRetryDelayMs
        while (systemSweepsPending && !shuttingDown) {
            delayMs = sleepBeforeRetry(delayMs) ?: return
            registerSweepsIfPending()
        }
    }

    /**
     * The stale-execution and retention sweep. A repeating trigger rather than a cron: the sweep has no
     * relationship to any user's schedule, and a cron would make it miss while this instance was down for
     * the very restart that leaves zombies.
     *
     * Registered on every node, including a disabled one. Sweeping is not scheduling — it touches no user
     * task and lives in its own Quartz group — and since the store became shared the job is a *cluster
     * singleton*: the row this node registers is the row whichever node fires runs, so reclamation keeps
     * happening while at least one node in the cluster is enabled and an inert node's own `stopTask` row is
     * reaped with the rest. This node keeps registering it because the write costs nothing and because the
     * row is then in the store for whichever node does start. What registering cannot buy is a sweep on a
     * deployment whose only replica has the flag off: `spring.quartz.auto-startup` follows that flag, so this
     * scheduler stays in standby and fires nothing it registers — such a deployment reclaims nothing until
     * some node runs with scheduling enabled, and that is a topology rule for the docs, not something a
     * registration can fix. Registering is legal in standby: Quartz only refuses a write after `shutdown()`,
     * so `spring.quartz.auto-startup=false` (which is what keeps a disabled node out of the cluster at all,
     * see application.yml) does not cost this job its registration.
     */
    private fun registerHousekeepingJob(): Boolean = registerSweep(
        label = "housekeeping",
        jobClass = SchedulerHousekeepingJob::class.java,
        jobKey = JobKey(SchedulerHousekeepingJob.JOB_NAME, SchedulerHousekeepingJob.GROUP),
        triggerKey = TriggerKey(SchedulerHousekeepingJob.JOB_NAME, SchedulerHousekeepingJob.GROUP),
        intervalMs = HOUSEKEEPING_INTERVAL_MINUTES * 60_000L,
    )

    /**
     * The convergence sweep. Cluster-singleton by construction: with a JDBC store the registration lands in
     * the shared store, so exactly one node fires it — which is what makes a periodic reconcile safe on two
     * instances where the same registration on a memory store ran twice.
     *
     * The interval is configurable because the integration tests have to freeze it: a sweep landing between
     * "hand the store some drift" and "reconcile it" would repair the drift first, and the assertion about
     * what one round did would then be about the wrong round.
     */
    private fun registerReconcileJob(): Boolean = registerSweep(
        label = "task reconcile",
        jobClass = SchedulerReconcileJob::class.java,
        jobKey = JobKey(SchedulerReconcileJob.JOB_NAME, SchedulerReconcileJob.GROUP),
        triggerKey = TriggerKey(SchedulerReconcileJob.JOB_NAME, SchedulerReconcileJob.GROUP),
        intervalMs = reconcileIntervalSeconds * 1000L,
    )

    /**
     * Put one repeating system sweep in the store, or leave the one the cluster already has — unless it sits
     * on a different period than this node was configured with, in which case the stored trigger is
     * replaced.
     *
     * `checkExists -> return`, the whole of what this method used to do on a hit, was harmless while the
     * store was per-process: the worst a second boot could cause was a duplicate sweep of its own. On a JDBC
     * cluster store the job row is shared, so that shape made *the first node to boot* the one that fixed the
     * period for every node in the cluster, and turned `SCHEDULER_RECONCILE_INTERVAL` on the others into a
     * number nothing would ever read again. Comparing the stored interval is what lets the configured value
     * win.
     *
     * @return false when the store refused the read or the write; the caller retries (see
     * [registerSweepsIfPending]), because a database that is merely slow at boot must not cost the cluster
     * its sweeps for the life of the process.
     */
    private fun registerSweep(
        label: String,
        jobClass: Class<out Job>,
        jobKey: JobKey,
        triggerKey: TriggerKey,
        intervalMs: Long,
    ): Boolean = try {
        if (scheduler.checkExists(jobKey)) {
            keepStoredIntervalOrMoveIt(jobKey, triggerKey, intervalMs, label)
        } else {
            scheduler.scheduleJob(
                JobBuilder.newJob(jobClass).withIdentity(jobKey).storeDurably().build(),
                sweepTrigger(jobKey, triggerKey, intervalMs),
            )
            log.info("Registered the {} sweep (every {}s)", label, intervalMs / 1000)
            true
        }
    } catch (e: Exception) {
        // Loud but fatal is the wrong way round here: a node that cannot sweep is degraded, and the converge
        // loop that follows is the one that decides whether it schedules anything at all.
        log.warn("The {} sweep could not be registered: {}", label, e.message)
        false
    }

    /**
     * The stored trigger decides the cluster's period, so this only speaks up when it disagrees. A job with
     * no trigger of ours to compare against — somebody registered it by hand, or a partial write left a
     * durable job — is left alone: this exists to move an interval, not to guess at a shape somebody else
     * chose.
     */
    private fun keepStoredIntervalOrMoveIt(
        jobKey: JobKey,
        triggerKey: TriggerKey,
        intervalMs: Long,
        label: String,
    ): Boolean {
        val stored = scheduler.getTriggersOfJob(jobKey)
            .filterIsInstance<SimpleTrigger>()
            .firstOrNull { it.key == triggerKey }
        if (stored == null) {
            log.info("The {} sweep is already registered, leaving it alone", label)
            return true
        }
        if (stored.repeatInterval == intervalMs) {
            log.info("The {} sweep is already registered (every {}s), leaving it alone", label, intervalMs / 1000)
            return true
        }
        scheduler.rescheduleJob(stored.key, sweepTrigger(jobKey, triggerKey, intervalMs))
        log.info(
            "Moved the {} sweep to the configured interval: {}s -> {}s",
            label,
            stored.repeatInterval / 1000,
            intervalMs / 1000,
        )
        return true
    }

    /** One system sweep's trigger: repeating, and starting now rather than on the next matching minute. */
    private fun sweepTrigger(
        jobKey: JobKey,
        triggerKey: TriggerKey,
        intervalMs: Long,
    ): Trigger = TriggerBuilder.newTrigger()
        .withIdentity(triggerKey)
        .forJob(jobKey)
        .startNow()
        .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMilliseconds(intervalMs).repeatForever())
        .build()

    @PreDestroy
    fun shutdownLoadExecutor() {
        shuttingDown = true
        loadExecutor.shutdownNow()
    }

    /**
     * Retry the initial converge until a round registers every active task: one attempt at startup used to
     * leave this instance scheduling nothing whenever MySQL was slower than the JVM. Failures go
     * through [SchedulerStatus] so /actuator/health reports DOWN instead of hiding it.
     *
     * The stale reclaim runs first and once: it is the row this process's own last death left behind, and
     * the reconcile itself is a diff over the schedule, which has no business sweeping the log table.
     */
    private fun loadTasksWithRetry() {
        expireStaleExecutions()
        var attempt = 0
        var delayMs = initialRetryDelayMs
        while (!shuttingDown) {
            attempt++
            // A sweep this node could not register at boot is retried from here, from the loop that has
            // always retried rounds: the store answering one write means it can answer the other, and the
            // alternative was a WARN line at startup and no sweep for the rest of the process's life. A
            // no-op on every healthy round — the flag is only set when the store refused us.
            registerSweepsIfPending()
            val failure: String? = try {
                val report = reconciler.reconcile()
                if (report.converged) {
                    if (attempt > 1) {
                        log.info("Reconciled agent tasks into the scheduler after {} attempts", attempt)
                    }
                    null
                } else {
                    status.lastReconcileError ?: "reconcile did not register every active task"
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                status.recordReconcileFailure(reason)
                metrics.recordReconcileRound(success = false)
                log.warn("Agent task reconcile attempt {} threw", attempt, e)
                reason
            }
            if (failure == null) {
                // One last chance, taken on the round that just proved the store answers: the attempt at the
                // top of this round could have run against a database still on its way up.
                registerSweepsIfPending()
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

    /**
     * Pure delegation: the service owns none of the converge. The diff lives in [TaskScheduleReconciler],
     * and the part this class used to do here — delete every job in the task group, then re-register from
     * the table — is the part a shared store cannot afford: on a JDBC store that first step is a
     * cluster-wide unschedule, taken by whichever node happened to restart or be told to reload.
     */
    override fun reconcileTasks(): ReconcileReport = reconciler.reconcile()

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
        // GROUP_ONCE rather than the task group: reconcile deletes what the table no longer asks for, and a
        // click that is still waiting for its fire is nobody's stale schedule. The fire path reads this same
        // constant to decide it is running a user's intent and not a lagging cron.
        val jobKey = JobKey("AgentTask_${task.id}_ONCE_$uniqueId", TaskQuartzRegistrar.GROUP_ONCE)
        // Same contract as the cron registration: this store cannot hold an AgentTask, and the job re-reads
        // the row when it fires (`AbstractAgentTaskJob.taskToRun`).
        val jobDataMap = JobDataMap().apply { put(TaskQuartzRegistrar.KEY_TASK_ID, task.id.toString()) }
        val jobDetail = JobBuilder.newJob(TaskQuartzRegistrar.jobClassFor(task))
            .withIdentity(jobKey)
            .usingJobData(jobDataMap)
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(TriggerKey("AgentTask_${task.id}_ONCE_${uniqueId}_trigger", TaskQuartzRegistrar.GROUP_ONCE))
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
     * registered nothing, so `scheduler.enabled` has to be answered at fire time rather than at reconcile
     * time: a round that runs on the other node cannot un-hand this node a trigger Quartz already gave it.
     */
    override val schedulingEnabled: Boolean
        get() = schedulerEnabled

    /**
     * Reclaim executions that outran their own timeout, judged per row in SQL.
     *
     * This is a *scan-type* UPDATE over every row at 3/4, i.e. over the whole live end of `idx_status`,
     * and the runs that are starting at the same moment insert into exactly that range. On MySQL the two
     * fight over next-key/gap locks, and whichever one it rolls back loses real work: roll back the
     * insert and that Quartz fire does not execute at all, with only an error line to show for it. The
     * sweep therefore has no business running once per fire — see [expireStaleExecutionsThrottled].
     *
     * Three kinds of caller reach this: the startup converge and the housekeeping sweep (both unthrottled,
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
     * Nothing is lost by waiting. Global reclaim is housekeeping's job, and since the store became shared
     * that job is a *cluster singleton*: one row in `QRTZ_*`, fired every five minutes by whichever node
     * claims it — not one sweep per node, and not by a disabled node at all (auto-startup keeps it out of the
     * cluster, so it fires nothing; the single-node deployment with `scheduler.enabled=false` is the one shape
     * where nobody reclaims, which is the topology rule `docs/deploy-harnax-scheduler.md` carries). This call
     * site only ever asks "is the row I can see still live", and the answer is the same one a *successful*
     * sweep gave at most 30s ago — a row that crossed its own 1.5x deadline inside that window was not stale
     * when the last sweep looked at it. One that threw is not in that count (see [expireStaleExecutions]).
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

        /**
         * How often the stale/retention sweep repeats. A constant rather than a configured value, and now a
         * load-bearing one: the registration compares it against the interval already in the shared store,
         * so what this says is what the cluster runs.
         */
        private const val HOUSEKEEPING_INTERVAL_MINUTES = 5L

        /**
         * Floor between two stale-execution sweeps on this node. Deliberately far below housekeeping's
         * 5-minute round — so the throttle never suppresses the reclaim that is supposed to happen — and
         * far above the burst of statements one task can produce (a fire, its manual run, a retry), which
         * is what used to multiply the scan-type UPDATE.
         */
        private const val MIN_STALE_SWEEP_INTERVAL_MS = 30_000L
    }
}
