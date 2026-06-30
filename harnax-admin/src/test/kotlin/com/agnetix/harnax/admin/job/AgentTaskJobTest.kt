package com.agnetix.harnax.admin.job

import com.agnetix.harnax.admin.client.RouterClient
import com.agnetix.harnax.admin.service.AgentTaskLogService
import com.agnetix.harnax.admin.service.SessionService
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.agent.protocol.ChatResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.quality.Strictness
import org.quartz.JobDataMap
import org.quartz.JobDetail
import org.quartz.JobExecutionContext

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskJobTest {

    @Mock
    private lateinit var sessionService: SessionService

    @Mock
    private lateinit var routerClient: RouterClient

    @Mock
    private lateinit var agentTaskLogService: AgentTaskLogService

    @Mock
    private lateinit var context: JobExecutionContext

    @Mock
    private lateinit var jobDetail: JobDetail

    private lateinit var agentTaskJob: AgentTaskJob

    private lateinit var testTask: AgentTask
    private lateinit var testSession: Session
    private lateinit var testResponse: ChatResponse

    @BeforeEach
    fun setUp() {
        agentTaskJob = AgentTaskJob()

        // Inject mocks via reflection (since @Autowired is used)
        val sessionField = AgentTaskJob::class.java.getDeclaredField("sessionService")
        sessionField.isAccessible = true
        sessionField.set(agentTaskJob, sessionService)

        val routerField = AgentTaskJob::class.java.getDeclaredField("routerClient")
        routerField.isAccessible = true
        routerField.set(agentTaskJob, routerClient)

        val logField = AgentTaskJob::class.java.getDeclaredField("agentTaskLogService")
        logField.isAccessible = true
        logField.set(agentTaskJob, agentTaskLogService)

        testTask = AgentTask().apply {
            id = 1L
            tenantId = 1L
            name = "Daily News Summary"
            agentId = 100L
            agentName = "News Agent"
            prompt = "Summarize today's news"
            cronExpression = "0 0 9 * * ?"
            taskStatus = 1
            concurrent = 0
            timeoutSeconds = 300
            creator = "admin"
        }

        testSession = Session().apply {
            id = 42L
            sessionId = "sess-uuid-123"
            agentId = 100L
            name = "News Agent"
            status = 1
        }

        testResponse = ChatResponse(
            sessionId = "sess-uuid-123",
            content = "Today's news summary: ..."
        )

        `when`(context.jobDetail).thenReturn(jobDetail)
        `when`(agentTaskLogService.save(any())).thenReturn(true)
    }

    // ==================== Execute - Null Task ====================

    @Nested
    @DisplayName("Execute with missing task")
    inner class NullTaskTests {

        @Test
        fun `execute should return early when task is null in jobDataMap`() {
            val jobDataMap = JobDataMap()
            `when`(jobDetail.jobDataMap).thenReturn(jobDataMap)

            agentTaskJob.execute(context)

            verify(sessionService, never()).createForAgent(any(), any())
            verify(routerClient, never()).chat(any(), any())
            verify(agentTaskLogService, never()).save(any())
        }

        @Test
        fun `execute should return early when jobDataMap has wrong type`() {
            val jobDataMap = JobDataMap()
            jobDataMap["agentTask"] = "not-a-task"
            `when`(jobDetail.jobDataMap).thenReturn(jobDataMap)

            agentTaskJob.execute(context)

            verify(sessionService, never()).createForAgent(any(), any())
        }
    }

    // ==================== Execute - Success Path ====================

    @Nested
    @DisplayName("Execute - Success Path")
    inner class SuccessTests {

        @BeforeEach
        fun setupSuccess() {
            val jobDataMap = JobDataMap()
            jobDataMap["agentTask"] = testTask
            `when`(jobDetail.jobDataMap).thenReturn(jobDataMap)
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat("sess-uuid-123", "Summarize today's news")).thenReturn(testResponse)
            `when`(sessionService.deleteSession(42L)).thenReturn(true)
        }

        @Test
        fun `execute should create session, call router, and save success log`() {
            agentTaskJob.execute(context)

            verify(sessionService).createForAgent(100L, "admin")
            verify(routerClient).chat("sess-uuid-123", "Summarize today's news")

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())

            val savedLog = logCaptor.firstValue
            assertEquals(1L, savedLog.taskId)
            assertEquals("Daily News Summary", savedLog.taskName)
            assertEquals("Summarize today's news", savedLog.prompt)
            assertEquals("sess-uuid-123", savedLog.sessionId)
            assertEquals(1, savedLog.status) // success
            assertEquals("Today's news summary: ...", savedLog.response)
            assertNotNull(savedLog.startTime)
            assertNotNull(savedLog.endTime)
            assertTrue(savedLog.durationMs >= 0)
        }

        @Test
        fun `execute should delete session after successful execution`() {
            agentTaskJob.execute(context)

            verify(sessionService).deleteSession(42L)
        }

        @Test
        fun `execute should handle null tokenUsage gracefully`() {
            val responseNoTokens = ChatResponse(
                sessionId = "sess-uuid-123",
                content = "Response without tokens"
            )
            `when`(routerClient.chat(any(), any())).thenReturn(responseNoTokens)

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals("", logCaptor.firstValue.tokenUsage)
        }
    }

    // ==================== Execute - Failure Path ====================

    @Nested
    @DisplayName("Execute - Failure Path")
    inner class FailureTests {

        @BeforeEach
        fun setupFailure() {
            val jobDataMap = JobDataMap()
            jobDataMap["agentTask"] = testTask
            `when`(jobDetail.jobDataMap).thenReturn(jobDataMap)
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
        }

        @Test
        fun `execute should record failure when router throws exception`() {
            `when`(routerClient.chat(any(), any()))
                .thenThrow(RuntimeException("Router connection failed"))

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())

            val savedLog = logCaptor.firstValue
            assertEquals(0, savedLog.status) // failed
            assertTrue(savedLog.errorInfo.contains("Router connection failed"))
        }

        @Test
        fun `execute should delete session even when router fails`() {
            `when`(routerClient.chat(any(), any()))
                .thenThrow(RuntimeException("Router error"))

            agentTaskJob.execute(context)

            verify(sessionService).deleteSession(42L)
        }

        @Test
        fun `execute should record failure when session creation fails`() {
            `when`(sessionService.createForAgent(any(), any()))
                .thenThrow(RuntimeException("Agent not found"))

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())

            val savedLog = logCaptor.firstValue
            assertEquals(0, savedLog.status) // failed
            assertTrue(savedLog.errorInfo.contains("Agent not found"))
            assertNull(savedLog.sessionId) // session was never created
        }

        @Test
        fun `execute should not delete session when session creation failed`() {
            `when`(sessionService.createForAgent(any(), any()))
                .thenThrow(RuntimeException("Agent not found"))

            agentTaskJob.execute(context)

            verify(sessionService, never()).deleteSession(any())
        }

        @Test
        fun `execute should handle session deletion failure gracefully`() {
            `when`(routerClient.chat(any(), any())).thenReturn(testResponse)
            `when`(sessionService.deleteSession(42L))
                .thenThrow(RuntimeException("Delete failed"))

            // Should not throw
            assertDoesNotThrow { agentTaskJob.execute(context) }

            // Log should still be saved
            verify(agentTaskLogService).save(any())
        }

        @Test
        fun `execute should handle log save failure gracefully`() {
            `when`(routerClient.chat(any(), any())).thenReturn(testResponse)
            `when`(sessionService.deleteSession(42L)).thenReturn(true)
            `when`(agentTaskLogService.save(any()))
                .thenThrow(RuntimeException("DB error"))

            // Should not throw
            assertDoesNotThrow { agentTaskJob.execute(context) }
        }

        @Test
        fun `execute should truncate error info to 4000 chars`() {
            val longError = "x".repeat(5000)
            `when`(routerClient.chat(any(), any()))
                .thenThrow(RuntimeException(longError))

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals(4000, logCaptor.firstValue.errorInfo.length)
        }
    }

    // ==================== Execute - Edge Cases ====================

    @Nested
    @DisplayName("Execute - Edge Cases")
    inner class EdgeCaseTests {

        @BeforeEach
        fun setupEdge() {
            val jobDataMap = JobDataMap()
            jobDataMap["agentTask"] = testTask
            `when`(jobDetail.jobDataMap).thenReturn(jobDataMap)
        }

        @Test
        fun `execute should handle null response content`() {
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(any(), any())).thenReturn(
                ChatResponse(sessionId = "sess-uuid-123", content = "")
            )
            `when`(sessionService.deleteSession(42L)).thenReturn(true)

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals("", logCaptor.firstValue.response)
            assertEquals(1, logCaptor.firstValue.status) // still success
        }

        @Test
        fun `execute should record duration correctly`() {
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(any(), any())).thenReturn(testResponse)
            `when`(sessionService.deleteSession(42L)).thenReturn(true)

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())

            val savedLog = logCaptor.firstValue
            assertNotNull(savedLog.startTime)
            assertNotNull(savedLog.endTime)
            assertTrue(savedLog.endTime!!.isAfter(savedLog.startTime) || savedLog.endTime == savedLog.startTime)
        }

        @Test
        fun `execute should handle task with empty creator`() {
            val taskNoCreator = testTask.apply { creator = "" }
            val jobDataMap = JobDataMap()
            jobDataMap["agentTask"] = taskNoCreator
            `when`(jobDetail.jobDataMap).thenReturn(jobDataMap)
            `when`(sessionService.createForAgent(100L, "")).thenReturn(testSession)
            `when`(routerClient.chat(any(), any())).thenReturn(testResponse)
            `when`(sessionService.deleteSession(42L)).thenReturn(true)

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals("", logCaptor.firstValue.creator)
        }

        @Test
        fun `execute should handle concurrent task`() {
            val concurrentTask = testTask.apply { concurrent = 1 }
            val jobDataMap = JobDataMap()
            jobDataMap["agentTask"] = concurrentTask
            `when`(jobDetail.jobDataMap).thenReturn(jobDataMap)
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(any(), any())).thenReturn(testResponse)
            `when`(sessionService.deleteSession(42L)).thenReturn(true)

            agentTaskJob.execute(context)

            verify(sessionService).createForAgent(100L, "admin")
            verify(routerClient).chat(any(), any())

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals(1, logCaptor.firstValue.status) // success
        }

        @Test
        fun `execute should handle very long prompt`() {
            val longPrompt = "x".repeat(10000)
            val taskLongPrompt = testTask.apply { prompt = longPrompt }
            val jobDataMap = JobDataMap()
            jobDataMap["agentTask"] = taskLongPrompt
            `when`(jobDetail.jobDataMap).thenReturn(jobDataMap)
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(eq("sess-uuid-123"), eq(longPrompt))).thenReturn(testResponse)
            `when`(sessionService.deleteSession(42L)).thenReturn(true)

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals(longPrompt, logCaptor.firstValue.prompt)
        }

        @Test
        fun `execute should handle very long response content`() {
            val longResponse = "y".repeat(50000)
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(any(), any())).thenReturn(
                ChatResponse(sessionId = "sess-uuid-123", content = longResponse)
            )
            `when`(sessionService.deleteSession(42L)).thenReturn(true)

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals(longResponse, logCaptor.firstValue.response)
        }

        @Test
        fun `execute should handle deleteSession returning false`() {
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(any(), any())).thenReturn(testResponse)
            `when`(sessionService.deleteSession(42L)).thenReturn(false) // returns false, not throws

            // Should not throw
            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals(1, logCaptor.firstValue.status) // still success
        }

        @Test
        fun `execute should set taskLog taskId correctly`() {
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(any(), any())).thenReturn(testResponse)
            `when`(sessionService.deleteSession(42L)).thenReturn(true)

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals(testTask.id, logCaptor.firstValue.taskId)
            assertEquals(testTask.name, logCaptor.firstValue.taskName)
        }

        @Test
        fun `execute should handle response with tokenUsage`() {
            val tokenUsage = com.agnetix.harnax.agent.protocol.TokenUsage(
                inputTokens = 100,
                outputTokens = 200,
                totalTokens = 300,
                costTime = 1.5,
                timestamp = System.currentTimeMillis()
            )
            val responseWithTokens = ChatResponse(
                sessionId = "sess-uuid-123",
                content = "Response with tokens",
                tokenUsage = tokenUsage
            )
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(any(), any())).thenReturn(responseWithTokens)
            `when`(sessionService.deleteSession(42L)).thenReturn(true)

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            val savedLog = logCaptor.firstValue
            assertTrue(savedLog.tokenUsage.isNotEmpty())
        }

        @Test
        fun `execute should handle prompt with special characters`() {
            val specialPrompt = "你好世界\n\t\"special\" chars: <>&'@#$%"
            val taskSpecialPrompt = testTask.apply { prompt = specialPrompt }
            val jobDataMap = JobDataMap()
            jobDataMap["agentTask"] = taskSpecialPrompt
            `when`(jobDetail.jobDataMap).thenReturn(jobDataMap)
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(eq("sess-uuid-123"), eq(specialPrompt))).thenReturn(testResponse)
            `when`(sessionService.deleteSession(42L)).thenReturn(true)

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals(specialPrompt, logCaptor.firstValue.prompt)
        }

        @Test
        fun `execute should handle router returning error code`() {
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(any(), any()))
                .thenThrow(RuntimeException("Router returned error: Agent disabled"))

            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals(0, logCaptor.firstValue.status) // failed
            assertTrue(logCaptor.firstValue.errorInfo.contains("Agent disabled"))
        }

        @Test
        fun `execute should handle session creation and router both failing`() {
            `when`(sessionService.createForAgent(100L, "admin")).thenReturn(testSession)
            `when`(routerClient.chat(any(), any()))
                .thenThrow(RuntimeException("Router timeout"))
            `when`(sessionService.deleteSession(42L))
                .thenThrow(RuntimeException("Delete also failed"))

            // Should not throw
            agentTaskJob.execute(context)

            val logCaptor = argumentCaptor<AgentTaskLog>()
            verify(agentTaskLogService).save(logCaptor.capture())
            assertEquals(0, logCaptor.firstValue.status) // failed
            assertTrue(logCaptor.firstValue.errorInfo.contains("Router timeout"))
        }
    }
}
