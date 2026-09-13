package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import java.time.Duration

/**
 * What a fire may cost. `expireStale` is a scan-type UPDATE over every row at 3/4 — the live end of
 * `idx_status` — and the executions that are starting insert into that same range. MySQL resolves that
 * with next-key/gap locks, and the side it rolls back is the one that loses real work: roll back the
 * insert and that Quartz fire silently does not execute.
 *
 * So the sweep may not run once per fire. These cases pin the three properties that replace it: the
 * per-task read answers on its own when it sees nothing, the global reclaim behind a *live* row is rate
 * limited per process, and the unthrottled callers (housekeeping, the startup load) still get theirs and
 * count as the last sweep.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("fire 时点的僵尸回收节流（G7）")
class SchedulerStaleSweepThrottleTest {

    @Mock
    private lateinit var schedulerFactory: SchedulerFactoryBean

    @Mock
    private lateinit var quartz: org.quartz.Scheduler

    @Mock
    private lateinit var agentTaskMapper: AgentTaskMapper

    @Mock
    private lateinit var agentTaskLogMapper: AgentTaskLogMapper

    @Mock
    private lateinit var routerClient: RouterClient

    @Mock
    private lateinit var executionGuard: AgentTaskExecutionGuard

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        service = SchedulerServiceImpl(
            schedulerFactory,
            agentTaskMapper,
            agentTaskLogMapper,
            routerClient,
            executionGuard,
            SchedulerStatus(schedulerEnabled = true),
            SchedulerMetrics(SimpleMeterRegistry(), QuartzJobInventory(schedulerFactory)),
            jobInventory = QuartzJobInventory(schedulerFactory),
            executionTimeoutSeconds = TIMEOUT,
            schedulerEnabled = true,
        )
    }

    /**
     * The shape that used to cost a full-table sweep per fire: a task forbidding overlap already has a
     * live row, and several fires/asks land inside the same few seconds. One statement for all of them.
     */
    @Test
    fun `asks inside the window share one sweep`() {
        givenLiveRow()

        assertTrue(service.hasActiveRunningExecution(TASK_ID))
        assertTrue(service.hasActiveRunningExecution(TASK_ID))
        assertTrue(service.hasActiveRunningExecution(TASK_ID))

        verify(agentTaskLogMapper, times(1)).expireStale(eq(TIMEOUT))
    }

    /** The other half: the window is a rate limit, not a switch that turns reclaim off. */
    @Test
    fun `the sweep runs again once the window has passed`() {
        givenLiveRow()
        assertTrue(service.hasActiveRunningExecution(TASK_ID))

        // Move the clock back past the throttle instead of sleeping on it.
        service.lastStaleSweepAtNanos = System.nanoTime() - Duration.ofSeconds(31).toNanos()

        assertTrue(service.hasActiveRunningExecution(TASK_ID))
        verify(agentTaskLogMapper, times(2)).expireStale(eq(TIMEOUT))
    }

    /**
     * The common case, and the one that made the cost invisible: a task with nothing running has no row
     * to judge, so the answer comes from the indexed per-task read alone.
     */
    @Test
    fun `a fire that sees no live row never sweeps`() {
        whenever(agentTaskLogMapper.selectRunningByTaskId(TASK_ID)).thenReturn(emptyList())

        assertFalse(service.hasActiveRunningExecution(TASK_ID))

        verify(agentTaskLogMapper, never()).expireStale(anyInt())
    }

    /**
     * `runTaskOnce` used to pay for the sweep twice: once in its own guard, then again in the job it
     * schedules seconds later. One window now, so the second ask finds a sweep it can reuse.
     */
    @Test
    fun `a run-once and the fire it schedules share one sweep`() {
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(task(concurrent = 0))
        givenLiveRow()

        assertFalse(service.runTaskOnce(TASK_ID), "a live row and no overlap means the run is refused")
        // What the scheduled job asks before it starts work.
        assertTrue(service.hasActiveRunningExecution(TASK_ID))

        verify(agentTaskLogMapper, times(1)).expireStale(eq(TIMEOUT))
    }

    /**
     * Housekeeping is the global reclaim and must stay unthrottled — but it *counts* as this process's
     * last sweep, or a fire a second later would repeat the very statement the throttle exists to ration.
     */
    @Test
    fun `an unthrottled sweep resets the window a fire reads`() {
        givenLiveRow()

        service.expireStaleExecutions()
        service.expireStaleExecutions()
        assertTrue(service.hasActiveRunningExecution(TASK_ID))

        verify(agentTaskLogMapper, times(2)).expireStale(eq(TIMEOUT))
    }

    /** A task that permits overlap must not consult the store at all on the manual path. */
    @Test
    fun `a concurrent task's manual run pays neither for the read nor for the sweep`() {
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(task(concurrent = 1))
        whenever(executionGuard.tryAcquireLock(any(), any())).thenReturn(true)
        whenever(agentTaskLogMapper.insert(any())).thenAnswer {
            it.getArgument<AgentTaskLog>(0).id = 7L
            1
        }
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(1)

        assertTrue(service.runTaskOnce(TASK_ID))

        verify(agentTaskLogMapper, never()).selectRunningByTaskId(any())
        verify(agentTaskLogMapper, never()).expireStale(anyInt())
    }

    private fun givenLiveRow() {
        whenever(agentTaskLogMapper.expireStale(anyInt())).thenReturn(0)
        whenever(agentTaskLogMapper.selectRunningByTaskId(TASK_ID)).thenReturn(
            listOf(
                AgentTaskLog().apply {
                    id = 11L
                    taskId = TASK_ID
                    status = 3
                    sessionId = "sess-11"
                },
            ),
        )
    }

    private fun task(concurrent: Int) = AgentTask().apply {
        id = TASK_ID
        name = "Nightly"
        prompt = "go"
        this.concurrent = concurrent
    }

    companion object {
        private const val TASK_ID = 3L
        private const val TIMEOUT = 300
    }
}
