package com.agnetix.harnax.scheduler.service.impl

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.SchedulerHealthIndicator
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.AgentTaskNonConcurrentJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.CronScheduleBuilder
import org.quartz.JobBuilder
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.slf4j.LoggerFactory
import org.springframework.boot.health.contributor.Status
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Covers the startup converge loop: the path that used to run the load once, swallow nothing but log the
 * failure, and leave the instance scheduling zero jobs while every probe still answered OK.
 *
 * The reconciler is the *real* one here, over a mocked mapper, a mocked Quartz store and a mocked
 * registrar. That split follows the claims: what this suite pins down is what a round leaves behind in
 * [SchedulerStatus], in the meter registry and on /actuator/health, and those three are written by the
 * reconciler itself — so it is the one collaborator that cannot be a stub. The store *read* behind them is
 * real too (a `QuartzJobInventory` over the mocked factory), because the health detail and the gauge answer
 * off it. What the registrar does with a register call is its own suite's business; here a cron Quartz would
 * reject is simulated at that boundary (throw out of `register`, which is what really happens), and the diff
 * itself is [com.agnetix.harnax.scheduler.service.TaskScheduleReconcilerTest]'s job.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerStartupReconcileTest {

    @Mock
    private lateinit var schedulerFactory: SchedulerFactoryBean

    @Mock
    private lateinit var quartz: Scheduler

    @Mock
    private lateinit var agentTaskMapper: AgentTaskMapper

    @Mock
    private lateinit var agentTaskLogMapper: AgentTaskLogMapper

    @Mock
    private lateinit var routerClient: RouterClient

    @Mock
    private lateinit var executionGuard: AgentTaskExecutionGuard

    private val registry = SimpleMeterRegistry()

    private lateinit var status: SchedulerStatus

    private lateinit var jobInventory: QuartzJobInventory

    private lateinit var registrar: TaskQuartzRegistrar

    private lateinit var health: SchedulerHealthIndicator

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)

        status = SchedulerStatus(schedulerEnabled = true)
        // The live job count has one implementation, QuartzJobInventory, shared by the gauge and the
        // health detail; a real one over the mocked factory keeps both reads on the production path.
        jobInventory = QuartzJobInventory(schedulerFactory)
        registrar = mock<TaskQuartzRegistrar>()
        val metrics = SchedulerMetrics(registry, jobInventory)
        metrics.initMeters()
        service = SchedulerServiceImpl(
            schedulerFactory,
            agentTaskMapper,
            agentTaskLogMapper,
            routerClient,
            executionGuard,
            status,
            metrics,
            jobInventory = jobInventory,
            registrar = registrar,
            reconciler = TaskScheduleReconciler(agentTaskMapper, registrar, jobInventory, status, metrics),
            executionTimeoutSeconds = 300,
            reconcileIntervalSeconds = 60,
            schedulerEnabled = true,
        )
        // Same inventory the service delegates to, so the health detail goes through the read it uses in
        // production.
        health = SchedulerHealthIndicator(status, schedulerFactory, jobInventory)
    }

    @AfterEach
    fun stopReconcileExecutor() {
        service.shutdownLoadExecutor()
    }

    @Test
    fun `a database failure propagates out of a manual reconcile`() {
        whenever(agentTaskMapper.selectRunningTasks()).thenThrow(RuntimeException("connection refused"))

        assertThrows(RuntimeException::class.java) { service.reconcileTasks() }

        assertNull(status.lastReconcileAt, "a round that never reached the store is not a completed round")
    }

    @Test
    fun `a successful reconcile recovers health and reports the live job count`() {
        givenTasks(task(1L), task(2L))
        // The health detail is not the number the round remembered: it is read back off the store, which is
        // what keeps it honest across start/pause/CRUD. Stub that read with what the round registered.
        givenStoreHolds(1L, 2L)

        val report = service.reconcileTasks()

        assertTrue(report.converged)
        assertEquals(
            2,
            report.unchanged,
            "the store matching the table is the path this name claims: nothing rewritten",
        )
        assertEquals(0, report.added + report.updated + report.removed)
        assertEquals(2, status.lastReconcileJobCount, "reconcile bookkeeping still records what this round converged")
        assertEquals(2, health.health().details["scheduledJobCount"], "the detail is the live store content")
        assertNotNull(status.lastReconcileAt)
        assertEquals(Status.UP, health.health().status)
        // The gauge is wired to the same inventory read as the health detail, so a scrape here sees the
        // store rather than the startup number: that is the whole point of the real QuartzJobInventory.
        assertEquals(
            2.0,
            registry.get("scheduler.jobs.scheduled").gauge().value(),
            "the gauge has to follow the live store",
        )
    }

    /**
     * The reason this loop exists: MySQL can be slower than the JVM, and one round at startup used to
     * leave the instance scheduling nothing. Health has to show DOWN while the rounds are failing and
     * clear itself once one converges, with no restart in between.
     */
    @Test
    fun `the initial reconcile keeps retrying until the database answers`() {
        whenever(agentTaskMapper.selectRunningTasks())
            .thenThrow(RuntimeException("connection refused"))
            .thenReturn(listOf(task(7L)))

        service.onApplicationReady()

        var sawDown = false
        var observed = health.health()
        val deadline = System.currentTimeMillis() + RETRY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (observed.status == Status.DOWN) {
                sawDown = true
            }
            if (observed.status == Status.UP) {
                break
            }
            Thread.sleep(100L)
            observed = health.health()
        }

        assertTrue(sawDown, "health should report DOWN while the rounds are still failing")
        assertEquals(Status.UP, observed.status, "reconcile should recover once the database answers")
        assertEquals(1, status.lastReconcileJobCount)
        assertNull(status.lastReconcileError)
        assertEquals(
            1.0,
            registry.get("scheduler.reconcile.rounds").tag("outcome", "failure").counter().count(),
            "exactly one failed round expected",
        )
        assertEquals(1.0, registry.get("scheduler.reconcile.rounds").tag("outcome", "success").counter().count())
    }

    /**
     * The loop's last resort for a human, and the one behaviour of the startup retry that had no test
     * anywhere: after five rounds that all failed, this node is not "still trying", it is scheduling nothing,
     * and that has to reach the log at ERROR — exactly once, rather than as one line per round forever.
     *
     * The backoff is what the wait costs, so it is shortened rather than slept through: five real rounds would
     * sit in 2s + 4s + 8s + 16s of backoff to prove a log level.
     */
    @Test
    fun `a node whose rounds never converge says so at error level after five attempts`() {
        whenever(agentTaskMapper.selectRunningTasks()).thenThrow(RuntimeException("connection refused"))
        service.initialRetryDelayMs = 20L

        val events = captureSchedulerLogs {
            service.onApplicationReady()
            awaitRounds(ALERT_AFTER_ATTEMPTS)
            // Past the trigger point, to show the alert is a one-time statement and not one line per round.
            awaitRounds(ALERT_AFTER_ATTEMPTS + 2)
        }

        val alerts = events.filter {
            it.level == Level.ERROR && it.formattedMessage.contains("still not scheduling anything after 5 attempts")
        }
        assertEquals(
            1,
            alerts.size,
            "expected exactly one error-level alert, got " +
                events.filter { it.level == Level.ERROR }.map { it.formattedMessage },
        )
    }

    /**
     * The loop's other exit: a node that is stopping must not keep writing the shared store behind the
     * cluster's back. `shutdownLoadExecutor()` interrupts the sleep the retry is sitting in, and both the
     * interrupt and the `shuttingDown` flag have to end it.
     */
    @Test
    fun `shutting down stops the retry loop`() {
        whenever(agentTaskMapper.selectRunningTasks()).thenThrow(RuntimeException("connection refused"))

        service.onApplicationReady()
        awaitFirstRound()
        val roundsAtShutdown = roundsRun()
        service.shutdownLoadExecutor()

        Thread.sleep(SHUTDOWN_SETTLE_MS)
        assertEquals(roundsAtShutdown, roundsRun(), "the loop kept reconciling after shutdown was requested")
    }

    @Test
    fun `a round that registers no task at all reports incomplete and turns health down`() {
        givenTasks(task(1L), task(2L))
        refusingAllRegistrations()

        val report = service.reconcileTasks()

        assertEquals(listOf(1L, 2L), report.failedIds)
        assertEquals(0, status.lastReconcileJobCount, "nothing reached the store")
        assertNotNull(status.lastReconcileError)
        assertEquals(Status.DOWN, health.health().status)
    }

    /**
     * Used to read `UP`: a partial round cleared the error, so a node that lost one task to a bad cron
     * looked perfectly healthy. Drift is a failure signal now — the count still says how much is
     * scheduling, the error says what is not, and `converged` is what /reload answers with.
     */
    @Test
    fun `a round that registers only part of the active tasks keeps retrying`() {
        givenTasks(task(1L), task(2L, cron = INVALID_CRON))
        refusingRegistrationFor(INVALID_CRON)

        val report = service.reconcileTasks()

        assertTrue(!report.converged, "an incomplete round must be retried")
        assertEquals(1, status.lastReconcileJobCount, "the tasks that did register still count")
        assertNotNull(status.lastReconcileError, "a partial round must not clear the error")
        assertTrue(status.lastReconcileError!!.contains("ids=[2]"), "got: ${status.lastReconcileError}")
        assertEquals(Status.DOWN, health.health().status, "a node missing part of its tasks is not healthy")
        assertEquals(
            1.0,
            registry.get("scheduler.reconcile.rounds").tag("outcome", "failure").counter().count(),
            "the drift has to show up as a failed attempt, not a success",
        )
    }

    private fun givenTasks(vararg tasks: AgentTask) {
        whenever(agentTaskMapper.selectRunningTasks()).thenReturn(tasks.toList())
    }

    private fun refusingAllRegistrations() {
        doThrow(RuntimeException("invalid cron")).`when`(registrar).register(any())
    }

    private fun refusingRegistrationFor(cron: String) {
        doThrow(RuntimeException("invalid cron")).`when`(registrar).register(argThat { cronExpression == cron })
    }

    /**
     * What the store holds afterwards, as the inventory reads it: keys plus the durable job for each *and*
     * the cron its trigger carries. The trigger half is not decoration — a snapshot without it reads "no cron
     * to compare" and the round rewrites every job in it, which is the `updated` path, not the matching one.
     * A test that asserted "unchanged" over this fixture would have been asserting the wrong path.
     */
    private fun givenStoreHolds(vararg taskIds: Long) {
        whenever(quartz.getJobKeys(any())).thenReturn(taskIds.map { JobKey("AgentTask_$it", GROUP) }.toSet())
        taskIds.forEach { id ->
            val key = JobKey("AgentTask_$id", GROUP)
            whenever(quartz.getJobDetail(key)).thenReturn(
                JobBuilder.newJob(AgentTaskNonConcurrentJob::class.java)
                    .withIdentity(key)
                    .storeDurably()
                    .build(),
            )
            whenever(quartz.getTriggersOfJob(key)).thenReturn(
                listOf(
                    TriggerBuilder.newTrigger()
                        .withIdentity(TriggerKey("AgentTask_${id}_trigger", GROUP))
                        .withSchedule(CronScheduleBuilder.cronSchedule(DEFAULT_CRON))
                        .build(),
                ),
            )
        }
    }

    private fun awaitFirstRound() {
        val deadline = System.currentTimeMillis() + RETRY_WAIT_MS
        while (roundsRun() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L)
        }
        assertTrue(roundsRun() > 0, "the startup loop never reached its first reconcile round")
    }

    /** One round is exactly one `selectRunningTasks()`, which is the read the diff starts from. */
    private fun roundsRun(): Int = Mockito.mockingDetails(agentTaskMapper)
        .invocations.count { it.method.name == "selectRunningTasks" }

    /** Wait until the converge thread has reached [count] rounds, so a claim about round N is not a race. */
    private fun awaitRounds(count: Int) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (roundsRun() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        assertTrue(roundsRun() >= count, "the loop only reached ${roundsRun()} rounds of $count")
    }

    /**
     * Events the service logged while [block] ran. What a node reports when it has given up scheduling is a
     * claim about a log level, and a log level is the only return value that call has.
     */
    private fun captureSchedulerLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(SchedulerServiceImpl::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        return try {
            block()
            appender.list
        } finally {
            logger.detachAppender(appender)
        }
    }

    private fun task(
        id: Long,
        cron: String = DEFAULT_CRON,
    ) = AgentTask().apply {
        this.id = id
        name = "task-$id"
        prompt = "summarize today"
        cronExpression = cron
        concurrent = 0
        timeoutSeconds = 300
    }

    companion object {
        /** First backoff is 2s, so this leaves room for the retry without making the suite slow. */
        private const val RETRY_WAIT_MS = 20_000L

        /** Longer than the 2s first backoff, so a loop still alive would have run another round. */
        private const val SHUTDOWN_SETTLE_MS = 4_000L

        private const val INVALID_CRON = "definitely not a cron"
        private const val GROUP = "AgentTaskGroup"

        /** `SchedulerServiceImpl.ALERT_AFTER_ATTEMPTS`, which the error-level alert fires at. */
        private const val ALERT_AFTER_ATTEMPTS = 5

        /** Generous: the loop under test is on a 20ms backoff, not the production one. */
        private const val AWAIT_MS = 10_000L

        /** What a task is built with and what the stubbed store answers back, so the two can match. */
        private const val DEFAULT_CRON = "0 0/5 * * * ?"
    }
}
