package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper
import com.agnetix.harnax.scheduler.support.CallerContext
import com.github.pagehelper.PageHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * The execution-log read, pinned on this side of the move from `harnax-admin`'s `AgentTaskLogServiceImpl`.
 *
 * There is one rule on this table and it lives in the SQL join: a row is readable through the task that owns
 * it, so `is_public = 1 OR creator = ?` decides what a caller sees. The service's whole contribution is to
 * tell the mapper who is asking — with the seven arguments below and no eighth — so these cases are about
 * what reaches `selectLogList`, not about what comes back.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskLogQueryServiceImplTest {

    @Mock
    private lateinit var agentTaskLogMapper: AgentTaskLogMapper

    private lateinit var service: AgentTaskLogQueryServiceImpl

    @BeforeEach
    fun setUp() {
        service = AgentTaskLogQueryServiceImpl(agentTaskLogMapper)
        CallerContext.set(CallerContext.Caller(callerId = "harnax-admin", username = "admin", tenantId = null))
        whenever(agentTaskLogMapper.selectLogList(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(emptyList())
    }

    @AfterEach
    fun tearDown() {
        CallerContext.clear()
        // startPage() against a mocked mapper never runs a query, so the page would otherwise be read back
        // by whichever test runs next on this thread.
        PageHelper.clearPage()
    }

    @Test
    @DisplayName("七个过滤参数原样交给 mapper")
    fun `page passes every filter through to the mapper`() {
        service.page(1L, "Daily", 1, "2026-07-01 00:00:00", "2026-07-31 23:59:59", "keyword", 1, 10)

        verify(agentTaskLogMapper).selectLogList(1L, "Daily", 1, "2026-07-01 00:00:00", "2026-07-31 23:59:59", "keyword", "admin")
    }

    @Test
    fun `page passes absent filters as nulls rather than as empty strings`() {
        service.page(1L, null, null, null, null, null, 1, 10)

        verify(agentTaskLogMapper).selectLogList(1L, null, null, null, null, null, "admin")
    }

    @Test
    fun `page passes a time range on its own`() {
        service.page(null, null, null, "2026-07-01 00:00:00", "2026-07-31 23:59:59", null, 1, 10)

        verify(agentTaskLogMapper).selectLogList(null, null, null, "2026-07-01 00:00:00", "2026-07-31 23:59:59", null, "admin")
    }

    @Test
    fun `page passes a keyword on its own`() {
        service.page(null, null, null, null, null, "error", 1, 10)

        verify(agentTaskLogMapper).selectLogList(null, null, null, null, null, "error", "admin")
    }

    /**
     * The visibility rule is the caller's name, so the mapper has to be told who is asking — captured rather
     * than hard-coded here, so the assertion reads back what the call actually carried. And the forwarded
     * tenant must not reach the query: it would make this read stricter than the task list, leaving a task
     * displayed while its own history answers empty after the owner switched tenant.
     */
    @Test
    @DisplayName("读取按属主而不是按租户")
    fun `page scopes the query to the caller and ignores the forwarded tenant`() {
        CallerContext.set(CallerContext.Caller(callerId = "harnax-admin", username = "alice", tenantId = 7L))

        service.page(null, null, null, null, null, null, 1, 10)

        val username = argumentCaptor<String>()
        verify(agentTaskLogMapper)
            .selectLogList(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), username.capture())
        assertEquals("alice", username.firstValue)

        // Same request with no tenant at all: nothing about the query may change.
        CallerContext.set(CallerContext.Caller(callerId = "harnax-admin", username = "alice", tenantId = null))
        service.page(null, null, null, null, null, null, 1, 10)

        verify(agentTaskLogMapper, times(2))
            .selectLogList(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), eq("alice"))
    }

    @Test
    @DisplayName("分页窗口两端钳制，与搬迁前一致")
    fun `page clamps the window before asking for it`() {
        service.page(null, null, null, null, null, null, 0, 0)

        val local = PageHelper.getLocalPage<AgentTaskLog>()
        assertEquals(1, local!!.pageNum)
        assertEquals(1, local.pageSize, "a zero page size used to reach the driver as `LIMIT 0`")
    }

    @Test
    fun `a call with no user behind it is refused rather than answered with the public rows`() {
        CallerContext.set(CallerContext.Caller(callerId = "harnax-admin", username = null, tenantId = null))

        val error = assertThrows(RuntimeException::class.java) {
            service.page(null, null, null, null, null, null, 1, 10)
        }

        assertEquals("Not logged in", error.message)
    }

    @Test
    @DisplayName("读契约的参数个数不再长出租户位")
    fun `the log read contract still carries no tenant parameter`() {
        val logList = AgentTaskLogMapper::class.java.methods.first { it.name == "selectLogList" }
        assertEquals(
            7,
            logList.parameterCount,
            "selectLogList stays taskId/taskName/status/startFrom/startTo/keyword/currentUsername",
        )

        // The unguarded read that used to sit beside it must not come back on this side either: it read the
        // same rows with no join and no caller.
        assertTrue(
            AgentTaskLogMapper::class.java.methods.none { it.name == "selectByTaskId" },
            "no selectByTaskId bypassing the visibility join may exist here",
        )
    }

    @Test
    fun `convertToResponse maps a finished run field for field`() {
        val started = LocalDateTime.now().minusMinutes(2)
        val log = AgentTaskLog().apply {
            id = 7L
            taskId = 1L
            taskName = "Daily News"
            prompt = "Summarize today's news"
            response = "Here is the news"
            sessionId = "task-1-100-8f2c"
            status = 1
            errorInfo = ""
            tokenUsage = """{"prompt":10,"completion":20}"""
            startTime = started
            endTime = started.plusMinutes(1)
            durationMs = 60_000L
            creator = "admin"
            createTime = started
        }

        val response = service.convertToResponse(log)

        assertEquals(7L, response.id)
        assertEquals(1L, response.taskId)
        assertEquals("Daily News", response.taskName)
        assertEquals("Summarize today's news", response.prompt)
        assertEquals("Here is the news", response.response)
        assertEquals("task-1-100-8f2c", response.sessionId)
        assertEquals(1, response.status)
        assertEquals("", response.errorInfo)
        assertEquals("""{"prompt":10,"completion":20}""", response.tokenUsage)
        assertEquals(started, response.startTime)
        assertEquals(started.plusMinutes(1), response.endTime)
        assertEquals(60_000L, response.durationMs)
        assertEquals("admin", response.creator)
        assertEquals(started, response.createTime)
    }

    @Test
    fun `convertToResponse keeps a not-yet-finished run visibly unfinished`() {
        val log = AgentTaskLog().apply {
            id = 8L
            status = 3
            startTime = null
            endTime = null
            durationMs = 0L
        }

        val response = service.convertToResponse(log)

        assertNull(response.startTime, "a running row has no start time written yet")
        assertNull(response.endTime)
        assertEquals(3, response.status)
    }
}
