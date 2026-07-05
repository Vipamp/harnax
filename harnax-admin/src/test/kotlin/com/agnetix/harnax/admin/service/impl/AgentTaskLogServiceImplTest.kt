package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
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
import org.mockito.quality.Strictness
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskLogServiceImplTest {

    @Mock
    private lateinit var agentTaskLogMapper: AgentTaskLogMapper

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
    }

    @Nested
    @DisplayName("Save Log Tests")
    inner class SaveLogTests {

        @Test
        fun `save should insert log successfully`() {
            `when`(agentTaskLogMapper.insert(any())).thenReturn(1)

            val result = agentTaskLogService.save(testLog)

            assertTrue(result)
            verify(agentTaskLogMapper).insert(testLog)
        }

        @Test
        fun `save should return false when insert fails`() {
            `when`(agentTaskLogMapper.insert(any())).thenReturn(0)

            val result = agentTaskLogService.save(testLog)

            assertFalse(result)
        }

        @Test
        fun `save should handle success status log`() {
            testLog.status = 1
            testLog.errorInfo = ""
            `when`(agentTaskLogMapper.insert(any())).thenReturn(1)

            val result = agentTaskLogService.save(testLog)

            assertTrue(result)
            assertEquals(1, testLog.status)
        }

        @Test
        fun `save should handle failure status log`() {
            testLog.status = 0
            testLog.errorInfo = "Connection timeout"
            `when`(agentTaskLogMapper.insert(any())).thenReturn(1)

            val result = agentTaskLogService.save(testLog)

            assertTrue(result)
            assertEquals(0, testLog.status)
            assertEquals("Connection timeout", testLog.errorInfo)
        }

        @Test
        fun `save should handle timeout status log`() {
            testLog.status = 2
            testLog.errorInfo = "Execution timed out after 300 seconds"
            `when`(agentTaskLogMapper.insert(any())).thenReturn(1)

            val result = agentTaskLogService.save(testLog)

            assertTrue(result)
            assertEquals(2, testLog.status)
        }
    }

    @Nested
    @DisplayName("Get Logs By Task ID Tests")
    inner class GetLogsByTaskIdTests {

        @Test
        fun `getLogsByTaskId should return logs for given task`() {
            val logs = listOf(testLog, testLog.apply { id = 2L })
            `when`(agentTaskLogMapper.selectByTaskId(100L)).thenReturn(logs)

            val result = agentTaskLogService.getLogsByTaskId(100L)

            assertEquals(2, result.size)
            verify(agentTaskLogMapper).selectByTaskId(100L)
        }

        @Test
        fun `getLogsByTaskId should return empty list when no logs`() {
            `when`(agentTaskLogMapper.selectByTaskId(999L)).thenReturn(emptyList())

            val result = agentTaskLogService.getLogsByTaskId(999L)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `getLogsByTaskId should call mapper with correct taskId`() {
            `when`(agentTaskLogMapper.selectByTaskId(42L)).thenReturn(emptyList())

            agentTaskLogService.getLogsByTaskId(42L)

            verify(agentTaskLogMapper).selectByTaskId(42L)
        }
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

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageTests {

        @Test
        fun `page should pass all parameters to mapper`() {
            `when`(agentTaskLogMapper.selectLogList(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())

            agentTaskLogService.page(1L, "Daily", 1, "2026-07-01 00:00:00", "2026-07-31 23:59:59", "keyword", 1, 10)

            verify(agentTaskLogMapper).selectLogList(1L, "Daily", 1, "2026-07-01 00:00:00", "2026-07-31 23:59:59", "keyword")
        }

        @Test
        fun `page should pass null filters correctly`() {
            `when`(agentTaskLogMapper.selectLogList(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())

            agentTaskLogService.page(1L, null, null, null, null, null, 1, 10)

            verify(agentTaskLogMapper).selectLogList(1L, null, null, null, null, null)
        }

        @Test
        fun `page should pass only time range filter`() {
            `when`(agentTaskLogMapper.selectLogList(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())

            agentTaskLogService.page(null, null, null, "2026-07-01 00:00:00", "2026-07-31 23:59:59", null, 1, 10)

            verify(agentTaskLogMapper).selectLogList(null, null, null, "2026-07-01 00:00:00", "2026-07-31 23:59:59", null)
        }

        @Test
        fun `page should pass only keyword filter`() {
            `when`(agentTaskLogMapper.selectLogList(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())

            agentTaskLogService.page(null, null, null, null, null, "error", 1, 10)

            verify(agentTaskLogMapper).selectLogList(null, null, null, null, null, "error")
        }
    }
}
