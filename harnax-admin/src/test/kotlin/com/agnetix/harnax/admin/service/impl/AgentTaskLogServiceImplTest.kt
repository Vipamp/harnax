package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.service.AgentTaskLogService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskLogServiceImplTest {

    @Mock
    private lateinit var agentTaskLogMapper: AgentTaskLogMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @InjectMocks
    private lateinit var agentTaskLogService: AgentTaskLogServiceImpl

    private lateinit var testLog: AgentTaskLog

    @BeforeEach
    fun setUp() {
        testLog = AgentTaskLog().apply {
            id = 1L
            taskId = 100L
            taskName = "Daily News"
            prompt = "Summarize today's news"
            response = "Here is the summary..."
            sessionId = "sess-uuid-123"
            status = 1
            errorInfo = ""
            tokenUsage = """{"input":100,"output":200}"""
            startTime = LocalDateTime.now().minusSeconds(30)
            endTime = LocalDateTime.now()
            durationMs = 30000
            creator = "admin"
            createTime = LocalDateTime.now()
        }

        // page() resolves the caller from the request, so the list tests need a logged-in context.
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))
        `when`(jwtUtil.validateToken(any())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
        TenantContext.clear()
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {
        @Test
        fun `convertToResponse should map all fields correctly`() {
            val response = agentTaskLogService.convertToResponse(testLog)

            assertEquals(1L, response.id)
            assertEquals(100L, response.taskId)
            assertEquals("Daily News", response.taskName)
            assertEquals("Summarize today's news", response.prompt)
            assertEquals("Here is the summary...", response.response)
            assertEquals("sess-uuid-123", response.sessionId)
            assertEquals(1, response.status)
            assertEquals(30000, response.durationMs)
            assertEquals("admin", response.creator)
        }

        @Test
        fun `convertToResponse should handle null optional fields`() {
            val logWithNulls = AgentTaskLog().apply {
                id = 2L
                taskId = 100L
                taskName = ""
                prompt = ""
                response = ""
                sessionId = ""
                status = 0
                errorInfo = ""
                tokenUsage = ""
                startTime = null
                endTime = null
                durationMs = 0
            }

            val response = agentTaskLogService.convertToResponse(logWithNulls)

            assertEquals(2L, response.id)
            assertEquals("", response.prompt)
            assertEquals("", response.response)
            assertNull(response.startTime)
            assertNull(response.endTime)
            assertEquals(0, response.durationMs)
        }

        @Test
        fun `convertToResponse should handle error log`() {
            val errorLog = AgentTaskLog().apply {
                id = 3L
                taskId = 100L
                taskName = "Failed Task"
                prompt = "Do something"
                response = ""
                sessionId = "sess-err"
                status = 0
                errorInfo = "Router connection failed: Connection refused"
                startTime = LocalDateTime.now().minusSeconds(5)
                endTime = LocalDateTime.now()
                durationMs = 5000
            }

            val response = agentTaskLogService.convertToResponse(errorLog)

            assertEquals(0, response.status)
            assertTrue(response.errorInfo.contains("Connection refused"))
            assertEquals("", response.response)
        }

        @Test
        fun `convertToResponse should preserve token usage JSON`() {
            val response = agentTaskLogService.convertToResponse(testLog)

            assertEquals("""{"input":100,"output":200}""", response.tokenUsage)
        }

        @Test
        fun `convertToResponse should handle long duration`() {
            testLog.durationMs = 3600000 // 1 hour
            val response = agentTaskLogService.convertToResponse(testLog)

            assertEquals(3600000, response.durationMs)
        }
    }

    /**
     * The log read carries no tenant argument (R2): the visibility gate is the caller's username, applied
     * through the owning task, exactly like the task list. The pass-through cases below therefore pin the
     * seven arguments the mapper actually takes.
     */
    @Nested
    @DisplayName("Page Query Tests")
    inner class PageTests {

        private fun givenEmptyLogList() {
            `when`(
                agentTaskLogMapper.selectLogList(
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    any(),
                ),
            ).thenReturn(emptyList())
        }

        @Test
        fun `page should pass all parameters to mapper`() {
            givenEmptyLogList()

            agentTaskLogService.page(1L, "Daily", 1, "2026-07-01 00:00:00", "2026-07-31 23:59:59", "keyword", 1, 10)

            verify(agentTaskLogMapper)
                .selectLogList(1L, "Daily", 1, "2026-07-01 00:00:00", "2026-07-31 23:59:59", "keyword", "admin")
        }

        @Test
        fun `page should pass null filters correctly`() {
            givenEmptyLogList()

            agentTaskLogService.page(1L, null, null, null, null, null, 1, 10)

            verify(agentTaskLogMapper).selectLogList(1L, null, null, null, null, null, "admin")
        }

        @Test
        fun `page should pass only time range filter`() {
            givenEmptyLogList()

            agentTaskLogService.page(null, null, null, "2026-07-01 00:00:00", "2026-07-31 23:59:59", null, 1, 10)

            verify(agentTaskLogMapper)
                .selectLogList(null, null, null, "2026-07-01 00:00:00", "2026-07-31 23:59:59", null, "admin")
        }

        @Test
        fun `page should pass only keyword filter`() {
            givenEmptyLogList()

            agentTaskLogService.page(null, null, null, null, null, "error", 1, 10)

            verify(agentTaskLogMapper).selectLogList(null, null, null, null, null, "error", "admin")
        }

        /**
         * The visibility rule lives in the SQL join, so the one thing this service can get wrong is
         * failing to tell the mapper who is asking. The username is therefore captured rather than
         * hard-coded, so the assertion reads back what the call actually carried.
         *
         * The second half is the R2 regression guard: a `TenantContext` was exactly what made this read
         * stricter than the task list, so running it with and without one must produce the *same* call.
         */
        @Test
        fun `page should scope the query to the caller and ignore the request tenant`() {
            `when`(jwtUtil.getUsernameFromToken(any())).thenReturn("alice")
            givenEmptyLogList()

            TenantContext.setTenantId(7L)
            agentTaskLogService.page(null, null, null, null, null, null, 1, 10)

            val username = argumentCaptor<String>()
            verify(agentTaskLogMapper)
                .selectLogList(
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    username.capture(),
                )
            assertEquals("alice", username.firstValue)

            // Same request, no tenant on the context at all: nothing about the query may change.
            TenantContext.clear()
            agentTaskLogService.page(null, null, null, null, null, null, 1, 10)

            verify(agentTaskLogMapper, times(2))
                .selectLogList(
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    eq("alice"),
                )
        }

        /**
         * The mapper contract must not grow a tenant slot back: with the parameter gone there is no way
         * to pass one, which is the point of R2 (a caller passing `null` would have left a dead argument
         * in the interface). Runs without a database, so it is the guard that survives on a machine with
         * Docker off.
         */
        @Test
        fun `the log read contract carries no tenant parameter`() {
            val logList = AgentTaskLogMapper::class.java.methods.first { it.name == "selectLogList" }
            assertEquals(
                7,
                logList.parameterCount,
                "selectLogList 应为 taskId/taskName/status/startFrom/startTo/keyword/currentUsername，不得再有 tenantId",
            )

            val visibleById = AgentTaskLogMapper::class.java.methods.first { it.name == "selectVisibleById" }
            assertEquals(
                2,
                visibleById.parameterCount,
                "selectVisibleById 应为 id/currentUsername，不得再有 tenantId",
            )
        }

        /**
         * `selectByTaskId` read the same rows with no join and no caller, and `getLogsByTaskId` exposed it
         * on the service. Both went: an execution log carries another user's prompt and response verbatim,
         * and a read that bypasses the visibility join makes the join a detail rather than a rule. This
         * asserts by name, because a method that comes back is otherwise invisible to every test here.
         */
        @Test
        fun `there is no unguarded read-by-task-id left to bypass the visibility join`() {
            assertTrue(
                AgentTaskLogMapper::class.java.methods.none { it.name == "selectByTaskId" },
                "mapper 上不得再出现无属主过滤的 selectByTaskId",
            )
            assertTrue(
                AgentTaskLogService::class.java.methods.none { it.name == "getLogsByTaskId" },
                "service 上不得再出现绕过可见性门禁的 getLogsByTaskId",
            )
        }
    }
}
