package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.Scheduler
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import java.time.LocalDateTime

/**
 * The stop signal is a status on the log row, not a memory on one node: any node can request a stop,
 * and only the thread that owns the execution closes the row out.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerStopStateMachineTest {

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
    fun `stop claims the running row and interrupts its session`() {
        whenever(agentTaskLogMapper.selectById(11L)).thenReturn(log(11L, status = 3, sessionId = "sess-11"))
        whenever(agentTaskLogMapper.markStopping(11L, "Stopping...")).thenReturn(1)

        assertTrue(service.stopTask(11L))

        verify(routerClient).sendCommand("sess-11", CommandType.INTERRUPT)
        // The executing node owns session cleanup; clearing it from here would destroy a live run.
        verify(routerClient, never()).clearSession(any())
    }

    @Test
    fun `stop leaves a finished execution alone`() {
        whenever(agentTaskLogMapper.selectById(12L)).thenReturn(log(12L, status = 1, sessionId = "sess-12"))

        assertFalse(service.stopTask(12L))

        verify(agentTaskLogMapper, never()).markStopping(any(), anyOrNull())
        verify(routerClient, never()).sendCommand(any(), any())
    }

    @Test
    fun `a repeated stop while already stopping still interrupts`() {
        whenever(agentTaskLogMapper.selectById(13L)).thenReturn(log(13L, status = 4, sessionId = "sess-13"))
        whenever(agentTaskLogMapper.markStopping(13L, "Stopping...")).thenReturn(0)

        assertTrue(service.stopTask(13L))

        verify(routerClient).sendCommand("sess-13", CommandType.INTERRUPT)
    }

    @Test
    fun `stop reports failure when the execution finished before the claim`() {
        whenever(agentTaskLogMapper.selectById(14L)).thenReturn(log(14L, status = 3, sessionId = "sess-14"))
        whenever(agentTaskLogMapper.markStopping(14L, "Stopping...")).thenReturn(0)

        assertFalse(service.stopTask(14L))

        verify(routerClient, never()).sendCommand(any(), any())
    }

    @Test
    fun `a successful execution closes its own row`() {
        whenever(routerClient.chat(any(), any())).thenReturn(ChatResponse(sessionId = "s", content = "done"))
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(1)

        service.executeTaskOnce(task(), LocalDateTime.now())

        val written = argumentCaptor<AgentTaskLog>()
        verify(agentTaskLogMapper).finishExecution(written.capture())
        assertEquals(1, written.firstValue.status)
        assertEquals("done", written.firstValue.response)
        assertEquals("", written.firstValue.errorInfo)
        verify(agentTaskLogMapper, never()).finalizeStopped(any())
    }

    @Test
    fun `a stopped execution reports stopped instead of its own result`() {
        whenever(routerClient.chat(any(), any())).thenReturn(ChatResponse(sessionId = "s", content = "partial"))
        // 0 rows: the row had already moved to status 4 by another node
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(0)

        service.executeTaskOnce(task(), LocalDateTime.now())

        val written = argumentCaptor<AgentTaskLog>()
        verify(agentTaskLogMapper).finalizeStopped(written.capture())
        assertEquals(5, written.firstValue.status)
        assertEquals("Task stopped by user", written.firstValue.errorInfo)
    }

    @Test
    fun `a row already finalised elsewhere is not rewritten`() {
        whenever(routerClient.chat(any(), any())).thenThrow(RuntimeException("router unavailable"))
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(0)
        whenever(agentTaskLogMapper.finalizeStopped(any())).thenReturn(0)

        service.executeTaskOnce(task(), LocalDateTime.now())

        val written = argumentCaptor<AgentTaskLog>()
        verify(agentTaskLogMapper).finalizeStopped(written.capture())
        assertEquals(5, written.firstValue.status)
    }

    @Test
    fun `a failed execution writes status 0 while the row is still running`() {
        whenever(routerClient.chat(any(), any())).thenThrow(RuntimeException("router unavailable"))
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(1)

        service.executeTaskOnce(task(), LocalDateTime.now())

        val written = argumentCaptor<AgentTaskLog>()
        verify(agentTaskLogMapper).finishExecution(written.capture())
        assertEquals(0, written.firstValue.status)
        assertEquals("router unavailable", written.firstValue.errorInfo)
        verify(agentTaskLogMapper, never()).finalizeStopped(any())
    }

    private fun task() = AgentTask().apply {
        id = 1L
        name = "Daily News"
        prompt = "summarize today"
        creator = "admin"
        concurrent = 0
        timeoutSeconds = 300
    }

    private fun log(
        id: Long,
        status: Int,
        sessionId: String,
    ) = AgentTaskLog().apply {
        this.id = id
        taskId = 1L
        taskName = "Daily News"
        this.status = status
        this.sessionId = sessionId
        startTime = LocalDateTime.now().minusSeconds(5)
    }
}
