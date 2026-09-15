package com.agnetix.harnax.scheduler.service.impl

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
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.atLeastOnce
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.Job
import org.quartz.JobBuilder
import org.quartz.JobDetail
import org.quartz.JobExecutionContext
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.SchedulerContext
import org.quartz.SchedulerException
import org.quartz.SimpleScheduleBuilder
import org.quartz.SimpleTrigger
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import java.time.Duration
import java.time.LocalDateTime
import java.util.Date

/**
 * Two halves of the same promise: the sweep has to be *registered* (the guard table had no caller that
 * ever removed a row), and what it calls has to reach the database with the retention and the timeout
 * baseline it is supposed to use.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerHousekeepingTest {

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

    private lateinit var jobInventory: QuartzJobInventory

    /** Stubbed because this suite is about what boot *registers*; its own behaviour is pinned elsewhere. */
    private val reconciler = mock<TaskScheduleReconciler>()

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.checkExists(any<JobKey>())).thenReturn(false)
        whenever(agentTaskMapper.selectRunningTasks()).thenReturn(emptyList())
        jobInventory = QuartzJobInventory(schedulerFactory)
        service = SchedulerServiceImpl(
            schedulerFactory,
            agentTaskMapper,
            agentTaskLogMapper,
            routerClient,
            executionGuard,
            SchedulerStatus(schedulerEnabled = true),
            SchedulerMetrics(SimpleMeterRegistry(), jobInventory),
            jobInventory = jobInventory,
            registrar = TaskQuartzRegistrar(schedulerFactory),
            reconciler = reconciler,
            executionTimeoutSeconds = TIMEOUT,
            reconcileIntervalSeconds = RECONCILE_INTERVAL,
            schedulerEnabled = true,
        )
    }

    @AfterEach
    fun tearDown() {
        service.shutdownLoadExecutor()
    }

    @Test
    fun `boot registers the housekeeping sweep as a repeating job`() {
        service.onApplicationReady()

        val trigger = scheduledTrigger(SchedulerHousekeepingJob::class.java)
        assertEquals(
            JobKey(SchedulerHousekeepingJob.JOB_NAME, SchedulerHousekeepingJob.GROUP),
            jobOf(trigger).key,
        )
        val simple = trigger as SimpleTrigger
        assertEquals(SimpleTrigger.REPEAT_INDEFINITELY, simple.repeatCount, "a one-shot sweep sweeps once")
        assertEquals(5 * 60 * 1000L, simple.repeatInterval, "the sweep is meant to run every five minutes")
    }

    /**
     * The sweep is what bounds the damage of a lost CRUD notification, and it is a Quartz job because the
     * shared store makes exactly one node fire it — which is what the same registration on a memory store
     * could not promise, since there it ran once per node.
     */
    @Test
    fun `an enabled node registers the reconcile sweep at the configured interval`() {
        service.onApplicationReady()

        val trigger = scheduledTrigger(SchedulerReconcileJob::class.java) as SimpleTrigger
        assertEquals(
            JobKey(SchedulerReconcileJob.JOB_NAME, SchedulerReconcileJob.GROUP),
            jobOf(trigger).key,
        )
        // One system group for both sweeps, and deliberately not the group the diff converges: a job there
        // that the task table does not account for is exactly what the next round deletes.
        assertEquals("SchedulerSystemGroup", SchedulerReconcileJob.GROUP)
        assertEquals(SchedulerHousekeepingJob.GROUP, SchedulerReconcileJob.GROUP)
        assertNotEquals(TaskQuartzRegistrar.GROUP_AGENT_TASK, SchedulerReconcileJob.GROUP)
        assertEquals(
            RECONCILE_INTERVAL * 1000L,
            trigger.repeatInterval,
            "the sweep runs on scheduler.reconcile-interval-seconds",
        )
        assertEquals(SimpleTrigger.REPEAT_INDEFINITELY, trigger.repeatCount)
    }

    /** A second boot on a store that kept the job must not put a second sweep on the same table. */
    @Test
    fun `an already registered sweep is not registered again`() {
        whenever(quartz.checkExists(any<JobKey>())).thenReturn(true)

        service.onApplicationReady()

        verify(quartz, never()).scheduleJob(any<JobDetail>(), any<Trigger>())
    }

    /**
     * The shape this used to have: one attempt, on the `ApplicationReadyEvent` thread, whose catch logged a
     * WARN and moved on. A database slower than the JVM therefore cost the cluster its sweeps for the rest of
     * the process's life — while the loop that keeps retrying reconcile rounds ran next to it and proved the
     * store answering. Under a shared store the sweep is not one idle node's business: it is the bound on
     * how long a lost CRUD notification stays invisible for everybody.
     */
    @Test
    fun `a sweep the store refused at boot is registered by a later round`() {
        // Boot's two `checkExists` calls are the two refusals; the retry from the converge loop is next.
        var refusals = 2
        whenever(quartz.checkExists(any<JobKey>())).thenAnswer {
            if (refusals-- > 0) throw SchedulerException("database has not answered yet") else false
        }
        whenever(reconciler.reconcile()).thenReturn(CONVERGED)

        service.onApplicationReady()

        awaitScheduled(SchedulerHousekeepingJob::class.java)
        awaitScheduled(SchedulerReconcileJob::class.java)
    }

    /**
     * A disabled node runs no converge loop, so it is the one node whose sweep would still be lost for good
     * — which matters most when the cluster has no enabled node, since this node's retry is then the only
     * thing that puts the row in the store. It is a row waiting for whoever eventually runs enabled to fire
     * it, not a sweep this node performs: with every node disabled nothing fires at all.
     */
    @Test
    fun `a disabled node retries the sweep it could not register`() {
        // One refusal: boot's attempt. The retry the node owes itself is the next call.
        var refusals = 1
        whenever(quartz.checkExists(any<JobKey>())).thenAnswer {
            if (refusals-- > 0) throw SchedulerException("database has not answered yet") else false
        }
        val disabled = disabledInstance()

        disabled.onApplicationReady()

        awaitScheduled(SchedulerHousekeepingJob::class.java)
        // Retrying the sweeps is not the same as taking scheduling work: the reconcile one is still off-limits.
        verify(quartz, never()).scheduleJob(
            argThat<JobDetail> { jobClass == SchedulerReconcileJob::class.java },
            any<Trigger>(),
        )
    }

    /**
     * `checkExists -> return` was harmless while the store was per-process. On a JDBC cluster store the job
     * row is shared, so that shape let the *first* node to boot fix the period for every node and made
     * `SCHEDULER_RECONCILE_INTERVAL` on the others a number nothing read again. Comparing the stored interval
     * is what lets the configured value win.
     */
    @Test
    fun `a sweep already in the store on another interval is moved to the configured one`() {
        givenStoreAlreadyHasTheSweeps(reconcileIntervalSeconds = RECONCILE_INTERVAL + 30)
        // Quartz answers the new trigger's next fire time. Left unstubbed it answers null, which since SF2
        // is the *refused* branch, and this case is about a move that landed.
        whenever(quartz.rescheduleJob(any<TriggerKey>(), any<Trigger>())).thenReturn(Date())

        service.onApplicationReady()

        val rescheduled = argumentAt<Trigger>(quartz, "rescheduleJob", 1)
        assertEquals(
            RECONCILE_INTERVAL * 1000L,
            (rescheduled as SimpleTrigger).repeatInterval,
            "the configured interval has to win over the one another node booted with",
        )
        verify(quartz, never()).scheduleJob(any<JobDetail>(), any<Trigger>())
    }

    /**
     * A move the store did not take: `rescheduleJob` answers null when the trigger it was handed is no
     * longer there — gone between the interval read and this write. Reporting that as "moved to the
     * configured interval" would leave the cluster on the old period with a log line saying otherwise, so
     * the node has to treat it as a refused write and try again from the loop that retries rounds.
     */
    @Test
    fun `a sweep move the store refused is owed and retried`() {
        givenStoreAlreadyHasTheSweeps(reconcileIntervalSeconds = RECONCILE_INTERVAL + 30)
        whenever(quartz.rescheduleJob(any<TriggerKey>(), any<Trigger>())).thenAnswer { null }
        whenever(reconciler.reconcile()).thenReturn(CONVERGED)
        service.initialRetryDelayMs = RETRY_DELAY_MS

        service.onApplicationReady()

        val reconcileKey = TriggerKey(SchedulerReconcileJob.JOB_NAME, SchedulerReconcileJob.GROUP)
        awaitUntil("the refused move is attempted again") {
            rescheduleAttempts(reconcileKey) >= 2
        }
    }

    /**
     * The same read when the stored period agrees: nothing is written. A reschedule resets NEXT_FIRE_TIME, so
     * a round that "fixed" a trigger that was already right would be doing to the system sweep exactly what
     * the reconcile diff exists to forbid for a user's schedule.
     */
    @Test
    fun `a sweep already on the configured interval is left exactly as it is`() {
        givenStoreAlreadyHasTheSweeps(reconcileIntervalSeconds = RECONCILE_INTERVAL)

        service.onApplicationReady()

        verify(quartz, never()).rescheduleJob(any<TriggerKey>(), any<Trigger>())
        verify(quartz, never()).scheduleJob(any<JobDetail>(), any<Trigger>())
    }

    /**
     * `scheduler.enabled=false` means "this node takes no scheduling work", not "nothing on this node may
     * ever write to the database again". Sweeping touches no user task and lives in its own Quartz group, so
     * an inert node still registers it — but read that for what it is now: with a shared store the sweep is
     * *one row in that store*, so reclamation keeps running while at least one node in the cluster is
     * enabled and a disabled node has no private reclaim path of its own. What registering here buys is that
     * the row is *in the store* for whichever node does run enabled to claim — it is emphatically **not**
     * that a cluster of nothing but disabled nodes sweeps: `spring.quartz.auto-startup` follows the same
     * flag, so no node of such a deployment starts its scheduler and the registered trigger is never fired.
     * That boundary is a topology rule and it is stated in `registerHousekeepingJob`'s KDoc, in
     * `application.yml` and in docs/deploy-harnax-scheduler.md. What registering also buys is that whoever
     * fires the job — any node claiming it, not only the one that wrote the row — finds its collaborators in
     * place. The task
     * *reconcile* is a scheduling write over the shared store, so it stays off: one enabled node registers
     * that sweep once, in the store, and the cluster fires it from there.
     */
    @Test
    fun `a disabled instance still registers the sweep but not the reconcile`() {
        val disabled = disabledInstance()

        disabled.onApplicationReady()

        assertEquals(
            SchedulerHousekeepingJob::class.java,
            jobOf(scheduledTrigger(SchedulerHousekeepingJob::class.java)).jobClass,
        )
        verify(quartz, never()).scheduleJob(
            argThat<JobDetail> { jobClass == SchedulerReconcileJob::class.java },
            any<Trigger>(),
        )
        verify(agentTaskMapper, never()).selectRunningTasks()
    }

    /**
     * Registration alone would be a fiction: the job takes its collaborators out of the Quartz scheduler
     * context, and `init()` used to fill that context behind the very same `scheduler.enabled` gate — so an
     * inert node could put a sweep on the clock that found nothing to call.
     *
     * What this pins is that half, and it is worth being exact about the shape: it calls
     * [SchedulerHousekeepingJob.execute] by hand on a context built by a disabled node. It is *not* a Quartz
     * fire and it does not claim a disabled node gets one — under a shared store whichever node claims the
     * trigger runs the sweep, and `/tasks/logs/{id}/stop` being open here only means this node can leave a
     * row at 4 for whoever fires it to reap.
     */
    @Test
    fun `a sweep fired on an inert node finds the collaborators its own stop needs`() {
        whenever(quartz.context).thenReturn(SchedulerContext())
        val disabled = disabledInstance()
        disabled.init()

        whenever(agentTaskLogMapper.selectById(21L)).thenReturn(stoppingLog(21L))
        whenever(agentTaskLogMapper.markStopping(eq(21L), anyOrNull())).thenReturn(1)
        // The router never answered, so nothing closes the row out: 4 is where it stays on this node.
        whenever(routerClient.sendCommand(any(), any())).thenReturn(CommandDelivery.Unanswered("connection refused"))
        assertTrue(disabled.stopTask(21L))

        val fired = mock<JobExecutionContext>()
        whenever(fired.scheduler).thenReturn(quartz)
        SchedulerHousekeepingJob().execute(fired)

        verify(agentTaskLogMapper).expireStale(eq(TIMEOUT))
    }

    /** The sweep's zombie baseline is the configured execution timeout, same key the fire path uses. */
    @Test
    fun `the stale sweep passes the configured timeout down to the mapper`() {
        service.expireStaleExecutions()

        verify(agentTaskLogMapper).expireStale(eq(TIMEOUT))
    }

    @Test
    fun `the log sweep cuts the retention window off create_time`() {
        val before = LocalDateTime.now()

        service.cleanupOldExecutionLogs(90)

        val captor = argumentCaptor<LocalDateTime>()
        verify(agentTaskLogMapper).deleteOldLogs(captor.capture())
        val age = Duration.between(captor.firstValue, LocalDateTime.now()).seconds
        assertTrue(
            age in (90L * 24 * 3600 - 60)..(90L * 24 * 3600 + 60),
            "cutoff $age seconds in the past is not 90 days, and $before was the clock then",
        )
    }

    /** Housekeeping must not be the thing that takes the instance down when one statement fails. */
    @Test
    fun `a failing log sweep swallows its own error`() {
        whenever(agentTaskLogMapper.deleteOldLogs(anyOrNull())).thenThrow(RuntimeException("lock wait timeout"))

        service.cleanupOldExecutionLogs(90)

        verify(agentTaskLogMapper).deleteOldLogs(anyOrNull())
    }

    /** Same collaborators as the enabled instance in `setUp`, only told not to schedule. */
    private fun disabledInstance() = SchedulerServiceImpl(
        schedulerFactory,
        agentTaskMapper,
        agentTaskLogMapper,
        routerClient,
        executionGuard,
        SchedulerStatus(schedulerEnabled = false),
        SchedulerMetrics(SimpleMeterRegistry(), jobInventory),
        jobInventory = jobInventory,
        registrar = TaskQuartzRegistrar(schedulerFactory),
        reconciler = reconciler,
        executionTimeoutSeconds = TIMEOUT,
        reconcileIntervalSeconds = RECONCILE_INTERVAL,
        schedulerEnabled = false,
    ).apply { initialRetryDelayMs = RETRY_DELAY_MS }

    /**
     * A store that has both sweeps already, as the *first* node to boot left them: `reconcileIntervalSeconds`
     * is the period that node happened to have configured, which is the thing the next node has to notice.
     */
    private fun givenStoreAlreadyHasTheSweeps(reconcileIntervalSeconds: Int) {
        whenever(quartz.checkExists(any<JobKey>())).thenReturn(true)
        val jobKey = JobKey(SchedulerReconcileJob.JOB_NAME, SchedulerReconcileJob.GROUP)
        whenever(quartz.getJobDetail(jobKey)).thenReturn(
            JobBuilder.newJob(SchedulerReconcileJob::class.java).withIdentity(jobKey).storeDurably().build(),
        )
        val stored = TriggerBuilder.newTrigger()
            .withIdentity(TriggerKey(jobKey.name, jobKey.group))
            .forJob(jobKey)
            .startNow()
            .withSchedule(
                SimpleScheduleBuilder.simpleSchedule()
                    .withIntervalInSeconds(reconcileIntervalSeconds)
                    .repeatForever(),
            )
            .build()
        whenever(quartz.getTriggersOfJob(jobKey)).thenReturn(listOf(stored))
    }

    /**
     * The registration retry runs on the converge thread, so "did it land" has to be waited for rather than
     * asserted the moment `onApplicationReady()` returns — which is itself the point of the finding: the boot
     * thread used to be the only one that ever tried.
     */
    private fun awaitScheduled(jobClass: Class<out Job>) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (jobClass in scheduledJobClasses()) return
            Thread.sleep(50L)
        }
        fail<Unit>(
            "job class $jobClass was never registered; the store got ${scheduledJobClasses().map { it.simpleName }}",
        )
    }

    /** Same waiting, for the assertions that are about a *retry* rather than about a first registration. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50L)
        }
        fail<Unit>("$what never happened within ${AWAIT_MS}ms")
    }

    /** How many times the sweep registration asked the store to move this trigger, refusals included. */
    private fun rescheduleAttempts(key: TriggerKey): Int = Mockito.mockingDetails(quartz).invocations
        .count { it.method.name == "rescheduleJob" && it.getArgument<TriggerKey>(0) == key }

    /** Every `scheduleJob` the mock has seen so far, as the job classes it was handed. */
    private fun scheduledJobClasses(): Set<Class<out Job>> = Mockito.mockingDetails(quartz).invocations
        .mapNotNull { call -> call.arguments.firstOrNull() as? JobDetail }
        .mapNotNull { it.jobClass }
        .toSet()

    /** One argument of one call the sweep registration made against the scheduler, read back for assertions. */
    private inline fun <reified T : Any> argumentAt(mock: Any, methodName: String, index: Int): T = Mockito.mockingDetails(mock).invocations
        .first { it.method.name == methodName }
        .getArgument<T>(index)

    /**
     * The boot path registers one system job per responsibility, so "what did it write" has to be answered
     * per job class rather than by assuming there is exactly one `scheduleJob` call to capture.
     */
    private fun scheduledPairs(): List<Pair<JobDetail, Trigger>> {
        val details = ArgumentCaptor.forClass(JobDetail::class.java)
        val triggers = ArgumentCaptor.forClass(Trigger::class.java)
        verify(quartz, atLeastOnce()).scheduleJob(details.capture(), triggers.capture())
        return details.allValues.zip(triggers.allValues)
    }

    private fun scheduledTrigger(jobClass: Class<out Job>): Trigger = scheduledPairs().first { it.first.jobClass == jobClass }.second

    private fun jobOf(trigger: Trigger): JobDetail = scheduledPairs().first { it.second === trigger }.first

    /** A row a stop put at 4: written by this node, owned by no execution it can still report on. */
    private fun stoppingLog(id: Long) = AgentTaskLog().apply {
        this.id = id
        taskId = 1L
        status = 4
        sessionId = "task-1-stopped"
        startTime = LocalDateTime.now().minusSeconds(60)
        createTime = LocalDateTime.now().minusSeconds(60)
    }

    companion object {
        private const val TIMEOUT = 300

        /** Not the 60 default: the assertion has to prove the value travels from the key to the trigger. */
        private const val RECONCILE_INTERVAL = 45

        /** The registration retry runs on the converge thread, so the cases above wait for it. */
        private const val AWAIT_MS = 10_000L

        /** Not the 2s production backoff: this suite is not waiting on a database that is not there. */
        private const val RETRY_DELAY_MS = 20L

        private val CONVERGED = ReconcileReport(
            added = 0,
            removed = 0,
            updated = 0,
            unchanged = 1,
            failedIds = emptyList(),
        )
    }
}
