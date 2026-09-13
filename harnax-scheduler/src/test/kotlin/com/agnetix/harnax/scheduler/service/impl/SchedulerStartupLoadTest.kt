package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.SchedulerHealthIndicator
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.JobKey
import org.quartz.Scheduler
import org.springframework.boot.health.contributor.Status
import org.springframework.scheduling.quartz.SchedulerFactoryBean

/**
 * Covers the startup load path: the previous implementation ran once, swallowed nothing but logged
 * the failure, and left the instance scheduling zero jobs while every probe still answered OK.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerStartupLoadTest {

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

    private lateinit var health: SchedulerHealthIndicator

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)

        status = SchedulerStatus(schedulerEnabled = true)
        // The live job count now has one implementation, QuartzJobInventory, shared by the gauge and the
        // health detail; a real one over the mocked factory keeps both reads on the production path.
        jobInventory = QuartzJobInventory(schedulerFactory)
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
            executionTimeoutSeconds = 300,
            schedulerEnabled = true,
        )
        // Same inventory the service delegates to, so the health detail goes through the read it uses in
        // production.
        health = SchedulerHealthIndicator(status, schedulerFactory, jobInventory)
    }

    @AfterEach
    fun stopLoadExecutor() {
        service.shutdownLoadExecutor()
    }

    @Test
    fun `a database failure propagates out of the load`() {
        whenever(agentTaskMapper.selectRunningTasks()).thenThrow(RuntimeException("connection refused"))

        assertThrows(RuntimeException::class.java) { service.loadTasksToScheduler() }

        assertNull(status.lastLoadSuccessAt)
    }

    @Test
    fun `a successful load recovers health and reports the live job count`() {
        whenever(agentTaskMapper.selectRunningTasks()).thenReturn(listOf(task(1L), task(2L)))
        // The health detail is no longer the number the load remembered: it is read back off the store,
        // which is what keeps it honest across start/pause/CRUD. Stub that read here.
        whenever(quartz.getJobKeys(any())).thenReturn(
            setOf(JobKey("AgentTask_1", "AgentTaskGroup"), JobKey("AgentTask_2", "AgentTaskGroup")),
        )

        service.loadTasksToScheduler()

        val healthDetails = health.health().details
        assertEquals(2, status.lastLoadJobCount, "load bookkeeping still records what this load registered")
        assertEquals(2, healthDetails["scheduledJobCount"], "the detail is the live store content")
        assertNotNull(status.lastLoadSuccessAt)
        assertEquals(Status.UP, health.health().status)
        // The gauge is wired to the same inventory read as the health detail, so a scrape here sees the
        // store rather than the startup number: that is the whole point of the real QuartzJobInventory.
        assertEquals(
            2.0,
            registry.get("scheduler.jobs.scheduled").gauge().value(),
            "the gauge has to follow the live store",
        )
    }

    @Test
    fun `the initial load keeps retrying until the database answers`() {
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

        assertTrue(sawDown, "health should report DOWN while the load is still failing")
        assertEquals(Status.UP, observed.status, "load should recover once the database answers")
        assertEquals(1, status.lastLoadJobCount)
        assertNull(status.lastLoadError)
        assertEquals(
            1.0,
            registry.get("scheduler.load.attempts").tag("outcome", "failure").counter().count(),
            "exactly one failed attempt expected",
        )
        assertEquals(1.0, registry.get("scheduler.load.attempts").tag("outcome", "success").counter().count())
    }

    @Test
    fun `a load that registers no task at all reports incomplete and turns health down`() {
        whenever(agentTaskMapper.selectRunningTasks())
            .thenReturn(listOf(task(1L, cron = "invalid"), task(2L, cron = "invalid")))

        val complete = service.loadTasksToScheduler()

        assertFalse(complete)
        assertEquals(0, status.lastLoadJobCount)
        assertNotNull(status.lastLoadError)
        assertEquals(Status.DOWN, health.health().status)
    }

    /**
     * Used to read `UP`: a partial success cleared the error, so a node that lost one task to a bad
     * cron looked perfectly healthy. Drift is a failure signal now — the count still says how much is
     * scheduling, the error says what is not.
     */
    @Test
    fun `a load that registers only part of the active tasks keeps retrying`() {
        whenever(agentTaskMapper.selectRunningTasks())
            .thenReturn(listOf(task(1L), task(2L, cron = "invalid")))

        val complete = service.loadTasksToScheduler()

        assertFalse(complete, "an incomplete load must be retried")
        assertEquals(1, status.lastLoadJobCount, "the tasks that did register still count")
        assertNotNull(status.lastLoadError, "a partial load must not clear the error")
        assertTrue(status.lastLoadError!!.contains("ids=[2]"), "got: ${status.lastLoadError}")
        assertEquals(Status.DOWN, health.health().status, "a node missing part of its tasks is not healthy")
        assertEquals(
            1.0,
            registry.get("scheduler.load.attempts").tag("outcome", "failure").counter().count(),
            "the drift has to show up as a failed load attempt, not a success",
        )
    }

    private fun task(id: Long, cron: String = "0 0/5 * * * ?") = AgentTask().apply {
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
    }
}
