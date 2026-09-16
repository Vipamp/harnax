package com.agnetix.harnax.scheduler.controller

import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

/**
 * Contract C5: the one read release 2 could not take away from admin.
 *
 * `McpSessionOwnerResolver.fromTask` needs the task's `creator` and `tenantId` to work out which human
 * owns an OAuth MCP token for a task session, and after the migration that row lives only in
 * `harnax_scheduler`. This endpoint is the whole of that answer, so the two things worth pinning are: it
 * says exactly the columns the caller reads, and a row that does not exist is a *successful* answer with
 * no data — which is how admin's own internal lookups report "not found", and what lets admin tell "no
 * such task" apart from "scheduler unreachable" on the other side of the call.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskOwnerControllerTest {

    @Mock
    private lateinit var agentTaskMapper: AgentTaskMapper

    private fun controller() = AgentTaskOwnerController(agentTaskMapper)

    private fun task(id: Long) = AgentTask().apply {
        this.id = id
        creator = "bob"
        tenantId = 5L
        agentId = 9L
    }

    @Test
    @DisplayName("C5: 返回 creator、tenantId、agentId 三值")
    fun `the owner answer carries the three values the row holds`() {
        whenever(agentTaskMapper.selectAnyById(7L)).thenReturn(task(7L))

        val result = controller().owner(7L)

        assertTrue(
            result.isSuccess(),
            "a task that exists must not answer as an error, got code=${result.code} message=${result.message}",
        )
        assertEquals("bob", result.data?.creator)
        assertEquals(5L, result.data?.tenantId)
        assertEquals(9L, result.data?.agentId)
    }

    @Test
    @DisplayName("C5: 行不存在时 code 200 且 data 为 null")
    fun `a task that does not exist answers success with no data`() {
        whenever(agentTaskMapper.selectAnyById(any())).thenReturn(null)

        val result = controller().owner(4242L)

        assertEquals(200, result.code)
        assertNull(result.data)
    }

    /**
     * `selectById` filters by *visibility to a named user*, and a service-to-service call has no user:
     * used here it would answer "no such task" for every task the caller cannot see, and admin would then
     * hand a task session no OAuth identity while the row sits there quite happily.
     */
    @Test
    @DisplayName("C5: 走无上下文的 selectAnyById 而非按可见性过滤的 selectById")
    fun `the lookup is the unscoped one because the caller has no user context`() {
        whenever(agentTaskMapper.selectAnyById(3L)).thenReturn(task(3L))

        controller().owner(3L)

        verify(agentTaskMapper).selectAnyById(3L)
        verify(agentTaskMapper, never()).selectById(any(), any())
    }
}
