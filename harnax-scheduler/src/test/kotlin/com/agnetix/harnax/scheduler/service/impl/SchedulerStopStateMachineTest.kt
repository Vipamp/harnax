package com.agnetix.harnax.scheduler.service.impl

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.scheduler.client.CommandDelivery
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.Scheduler
import org.slf4j.LoggerFactory
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import java.time.LocalDateTime
import ch.qos.logback.classic.Logger as LogbackLogger

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
        // An execution refuses to start without its log row (it is the only place the outcome can be
        // written), so the insert has to behave like a real one here: row counted, key written back.
        whenever(agentTaskLogMapper.insert(any())).thenAnswer {
            it.getArgument<AgentTaskLog>(0).id = 100L
            1
        }
        // One construction path for the whole suite (see serviceWith); the job count there is a real
        // QuartzJobInventory over the mocked factory, which keeps this suite honest even though nothing
        // here scrapes the gauge.
        service = serviceWith(timeoutSeconds = DEFAULT_TIMEOUT_SECONDS)
    }

    @Test
    fun `stop claims the running row and interrupts its session`() {
        whenever(agentTaskLogMapper.selectById(11L)).thenReturn(log(11L, status = 3, sessionId = "sess-11"))
        whenever(agentTaskLogMapper.markStopping(11L, "Stopping...")).thenReturn(1)
        whenever(routerClient.sendCommand("sess-11", CommandType.INTERRUPT)).thenReturn(CommandDelivery.Delivered)

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
        whenever(routerClient.sendCommand("sess-13", CommandType.INTERRUPT)).thenReturn(CommandDelivery.Delivered)

        assertTrue(service.stopTask(13L))

        verify(routerClient).sendCommand("sess-13", CommandType.INTERRUPT)
    }

    @Test
    fun `a stop the agent explicitly missed closes the row as stopped immediately`() {
        whenever(agentTaskLogMapper.selectById(15L)).thenReturn(log(15L, status = 3, sessionId = "sess-15"))
        whenever(agentTaskLogMapper.markStopping(15L, "Stopping...")).thenReturn(1)
        // agent-service was restarted, or the session mapping moved on: it answered and said nothing is
        // running, so no node will ever write this row's outcome.
        whenever(routerClient.sendCommand("sess-15", CommandType.INTERRUPT))
            .thenReturn(CommandDelivery.Missed("No live execution for this session on this instance"))

        assertTrue(service.stopTask(15L))

        val written = argumentCaptor<AgentTaskLog>()
        verify(agentTaskLogMapper).finalizeStopped(written.capture())
        assertEquals(5, written.firstValue.status)
        assertEquals("No live execution to interrupt", written.firstValue.errorInfo)
    }

    @Test
    fun `a missed stop whose row refused to close is an error, not a log line`() {
        whenever(agentTaskLogMapper.selectById(18L)).thenReturn(log(18L, status = 3, sessionId = "sess-18"))
        whenever(agentTaskLogMapper.markStopping(18L, "Stopping...")).thenReturn(1)
        whenever(routerClient.sendCommand("sess-18", CommandType.INTERRUPT))
            .thenReturn(CommandDelivery.Missed("No live execution for this session on this instance"))
        // 0 rows: the row had already moved out from under the stop, so this node wrote nothing and the
        // caller was still told the stop succeeded. This branch exists precisely so the reaper never has
        // to guess about this execution — guessing anyway would be the bug back again.
        whenever(agentTaskLogMapper.finalizeStopped(any())).thenReturn(0)

        val events = captureSchedulerLogs { service.stopTask(18L) }

        assertTrue(
            events.any { it.level == Level.ERROR && it.formattedMessage.contains("18") },
            "expected an ERROR naming log 18, got ${events.map { it.level.toString() }}",
        )
    }

    @Test
    fun `a command that never got an answer leaves the row stopping`() {
        whenever(agentTaskLogMapper.selectById(17L)).thenReturn(log(17L, status = 3, sessionId = "sess-17"))
        whenever(agentTaskLogMapper.markStopping(17L, "Stopping...")).thenReturn(1)
        // A router blip says nothing about whether the execution is alive somewhere; it is the same
        // shape as "not running" only on the outside.
        whenever(routerClient.sendCommand("sess-17", CommandType.INTERRUPT))
            .thenReturn(CommandDelivery.Unanswered("connection refused"))

        assertTrue(service.stopTask(17L))

        // Finalising here would label a live run as stopped and throw away whatever it reports back.
        verify(agentTaskLogMapper, never()).finalizeStopped(any())
    }

    @Test
    fun `a stop that did reach a live execution leaves the row to that node`() {
        whenever(agentTaskLogMapper.selectById(16L)).thenReturn(log(16L, status = 3, sessionId = "sess-16"))
        whenever(agentTaskLogMapper.markStopping(16L, "Stopping...")).thenReturn(1)
        whenever(routerClient.sendCommand("sess-16", CommandType.INTERRUPT)).thenReturn(CommandDelivery.Delivered)

        assertTrue(service.stopTask(16L))

        // The node owning the execution closes 4 -> 5; finalising here would pre-empt its real result.
        verify(agentTaskLogMapper, never()).finalizeStopped(any())
    }

    @Test
    fun `stop reports failure when the execution finished before the claim`() {
        whenever(agentTaskLogMapper.selectById(14L)).thenReturn(log(14L, status = 3, sessionId = "sess-14"))
        whenever(agentTaskLogMapper.markStopping(14L, "Stopping...")).thenReturn(0)

        assertFalse(service.stopTask(14L))

        verify(routerClient, never()).sendCommand(any(), any())
    }

    @Test
    fun `the expiry baseline follows the configured execution timeout, not a constant`() {
        whenever(agentTaskMapper.selectAnyById(1L)).thenReturn(task())
        whenever(agentTaskLogMapper.selectRunningByTaskId(1L)).thenReturn(listOf(log(31L, status = 3, sessionId = "sess-31")))
        whenever(agentTaskLogMapper.expireStale(any())).thenReturn(0)

        // A sweep on a service configured for 900s must judge zombies against 900s. The manual run itself
        // is rejected (the row above is live and this task forbids overlap) — only the argument the
        // sweep got is under test here.
        serviceWith(timeoutSeconds = 900).runTaskOnce(1L)

        val captor = argumentCaptor<Int>()
        verify(agentTaskLogMapper).expireStale(captor.capture())
        assertEquals(900, captor.firstValue)
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
        whenever(agentTaskLogMapper.selectById(any())).thenReturn(log(100L, status = 4, sessionId = "sess-1"))

        service.executeTaskOnce(task(), LocalDateTime.now())

        val written = argumentCaptor<AgentTaskLog>()
        verify(agentTaskLogMapper).finalizeStopped(written.capture())
        assertEquals(5, written.firstValue.status)
        assertEquals("Task stopped by user", written.firstValue.errorInfo)
    }

    @Test
    fun `a row the reaper mis-timed-out gets its real result written back`() {
        whenever(routerClient.chat(any(), any())).thenReturn(ChatResponse(sessionId = "s", content = "real output"))
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(0)
        // The row had been reaped as 2 while this execution was still running
        whenever(agentTaskLogMapper.selectById(any())).thenReturn(log(100L, status = 2, sessionId = "sess-21"))
        whenever(agentTaskLogMapper.reclaimExpired(any())).thenReturn(1)

        service.executeTaskOnce(task().apply { id = 21L }, LocalDateTime.now())

        val written = argumentCaptor<AgentTaskLog>()
        verify(agentTaskLogMapper).reclaimExpired(written.capture())
        assertEquals(1, written.firstValue.status)
        assertEquals("real output", written.firstValue.response)
        verify(agentTaskLogMapper, never()).finalizeStopped(any())
    }

    @Test
    fun `a stop the reaper outran mid-write still ends up stopped rather than timed out`() {
        whenever(routerClient.chat(any(), any())).thenReturn(ChatResponse(sessionId = "s", content = "partial"))
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(0)
        // The reachable race: this thread reads 4, and before its 4 -> 5 UPDATE another fire's
        // expireStale moves the row to 2. finalizeStopped then matches nothing, and leaving it at a
        // warning would report a run the user really stopped as a timeout — the same symptom G-series
        // exists to remove, reached from the other side.
        whenever(agentTaskLogMapper.selectById(any())).thenReturn(
            log(100L, status = 4, sessionId = "sess-23"),
            log(100L, status = 2, sessionId = "sess-23"),
        )
        whenever(agentTaskLogMapper.finalizeStopped(any())).thenReturn(0)
        whenever(agentTaskLogMapper.reclaimExpired(any())).thenReturn(1)

        service.executeTaskOnce(task().apply { id = 23L }, LocalDateTime.now())

        val written = argumentCaptor<AgentTaskLog>()
        verify(agentTaskLogMapper).reclaimExpired(written.capture())
        assertEquals(5, written.firstValue.status)
        assertEquals("Task stopped by user", written.firstValue.errorInfo)
    }

    @Test
    fun `a row already terminal for another reason is left alone`() {
        whenever(routerClient.chat(any(), any())).thenReturn(ChatResponse(sessionId = "s", content = "late"))
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(0)
        whenever(agentTaskLogMapper.selectById(any())).thenReturn(log(100L, status = 1, sessionId = "sess-22"))

        service.executeTaskOnce(task().apply { id = 22L }, LocalDateTime.now())

        verify(agentTaskLogMapper, never()).finalizeStopped(any())
        verify(agentTaskLogMapper, never()).reclaimExpired(any())
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

    /** One construction path for this suite, with the execution timeout left to the caller. */
    private fun serviceWith(timeoutSeconds: Int): SchedulerServiceImpl {
        val jobInventory = QuartzJobInventory(schedulerFactory)
        return SchedulerServiceImpl(
            schedulerFactory,
            agentTaskMapper,
            agentTaskLogMapper,
            routerClient,
            executionGuard,
            SchedulerStatus(schedulerEnabled = true),
            SchedulerMetrics(SimpleMeterRegistry(), jobInventory),
            jobInventory = jobInventory,
            registrar = TaskQuartzRegistrar(schedulerFactory),
            // This suite never reconciles: it walks the stop state machine.
            reconciler = mock<TaskScheduleReconciler>(),
            schedulerEnabled = true,
            executionTimeoutSeconds = timeoutSeconds,
            reconcileIntervalSeconds = 60,
        )
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

    /**
     * Events the service logged while [block] ran. Some refusals to write a row have no return value to
     * assert on — the level they are reported at is the whole contract.
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

    companion object {
        /** What `scheduler.timeout-seconds` defaults to in application.yml. */
        private const val DEFAULT_TIMEOUT_SECONDS = 300
    }
}
