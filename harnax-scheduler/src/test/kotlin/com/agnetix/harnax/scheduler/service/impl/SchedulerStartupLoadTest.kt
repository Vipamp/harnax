package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
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
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
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

    private lateinit var health: SchedulerHealthIndicator

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)

        status = SchedulerStatus(schedulerEnabled = true)
        val metrics = SchedulerMetrics(registry, status)
        metrics.initMeters()
        service = SchedulerServiceImpl(
            schedulerFactory,
            agentTaskMapper,
            agentTaskLogMapper,
            routerClient,
            executionGuard,
            status,
            metrics,
            schedulerEnabled = true,
        )
        health = SchedulerHealthIndicator(status, schedulerFactory)
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
    fun `a successful load publishes the job count to health`() {
        whenever(agentTaskMapper.selectRunningTasks()).thenReturn(listOf(task(1L), task(2L)))

        service.loadTasksToScheduler()

        assertEquals(2, status.scheduledJobCount)
        assertNotNull(status.lastLoadSuccessAt)
        assertEquals(Status.UP, health.health().status)
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
        assertEquals(1, status.scheduledJobCount)
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
        assertEquals(0, status.scheduledJobCount)
        assertNotNull(status.lastLoadError)
        assertEquals(Status.DOWN, health.health().status)
    }

    @Test
    fun `a load that registers only part of the active tasks keeps retrying`() {
        whenever(agentTaskMapper.selectRunningTasks())
            .thenReturn(listOf(task(1L), task(2L, cron = "invalid")))

        val complete = service.loadTasksToScheduler()

        assertFalse(complete, "an incomplete load must be retried")
        assertEquals(1, status.scheduledJobCount)
        assertEquals(Status.UP, health.health().status, "this node is scheduling, just not everything")
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
