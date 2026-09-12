package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.Scheduler
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import java.time.LocalDateTime

/**
 * A conflict has to leave the service as a return value: the controller turns false into business
 * code 40901, and an exception instead would degrade to a generic 500 for the caller. These cases run
 * against the real service — the controller tests mock it away and cannot see the difference.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerServiceImplTest {

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

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        val status = SchedulerStatus(schedulerEnabled = true)
        val metrics = SchedulerMetrics(SimpleMeterRegistry(), status)
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
    }

    @Test
    fun `a manual trigger on a task with a live execution is rejected as false, not as an exception`() {
        givenTaskWithActiveRunningLog()

        val rejected = assertDoesNotThrow { service.triggerManually(TASK_ID) }

        assertFalse(rejected)
        // Rejected before the cluster lock, so no other instance can be told to run it either.
        verify(executionGuard, never()).tryAcquireLock(any(), any())
    }

    @Test
    fun `a run-once on a task with a live execution is rejected as false, not as an exception`() {
        givenTaskWithActiveRunningLog()

        val rejected = assertDoesNotThrow { service.runTaskOnce(TASK_ID) }

        assertFalse(rejected)
        verify(quartz, never()).scheduleJob(any(), any())
    }

    /**
     * The running-log guard expires stale rows before reading, so that read is stubbed too — otherwise
     * a test would pass on a mocked-out mapper rather than on the guard's own decision.
     */
    private fun givenTaskWithActiveRunningLog() {
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(task())
        whenever(agentTaskLogMapper.expireStale(anyInt())).thenReturn(0)
        whenever(agentTaskLogMapper.selectRunningByTaskId(TASK_ID)).thenReturn(listOf(runningLog()))
        // Not the source of the rejection on purpose: if the log guard ever stops short-circuiting,
        // the manual-trigger case still has to fail instead of slipping through the lock path.
        whenever(executionGuard.tryAcquireLock(any(), any())).thenReturn(true)
    }

    private fun task() = AgentTask().apply {
        id = TASK_ID
        name = "Daily News"
        prompt = "summarize today"
        creator = "admin"
        concurrent = 0
        timeoutSeconds = 300
    }

    private fun runningLog() = AgentTaskLog().apply {
        id = 21L
        taskId = TASK_ID
        taskName = "Daily News"
        status = 3
        sessionId = "sess-21"
        startTime = LocalDateTime.now().minusSeconds(5)
    }

    companion object {
        private const val TASK_ID = 1L
    }
}
