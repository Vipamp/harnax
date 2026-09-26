package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.entity.ToolCallLogEntity
import com.agnetix.harnax.mapper.ToolCallLogMapper
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import tools.jackson.databind.ObjectMapper

/**
 * Unit tests for ToolCallLogAdaptorImpl.
 *
 * `tool_call_log.tenant_id` (V50) can only come from the info object the toolbox built, and the toolbox
 * built it from the session meta the launcher carried. This is the last hop, and the one a silent null
 * would make invisible rather than wrong-looking.
 */
class ToolCallLogAdaptorImplTest {

    private val inserted = mutableListOf<ToolCallLogEntity>()
    private lateinit var adaptor: ToolCallLogAdaptorImpl

    @BeforeEach
    fun setUp() {
        val toolCallLogMapper = mock(ToolCallLogMapper::class.java)
        `when`(toolCallLogMapper.insert(any())).thenAnswer { invocation ->
            invocation.getArgument<ToolCallLogEntity>(0).let { inserted += it }
            1
        }
        adaptor = ToolCallLogAdaptorImpl(toolCallLogMapper, ObjectMapper())
    }

    private fun info(tenantId: Long?) = ToolCallInfo(
        agentId = 11L,
        sessionId = "s-1",
        toolName = "read_file",
        args = mapOf("path" to "/tmp/a"),
        result = "ok",
        success = true,
        startTime = 1_700_000_000_000L,
        endTime = 1_700_000_001_000L,
        duration = 1_000L,
        tenantId = tenantId,
    )

    @Test
    @DisplayName("the call is stored under the tenant that owns the session")
    fun emitStoresTheSessionTenant() {
        adaptor.emit(info(tenantId = 5L))

        val row = inserted.single()
        assertEquals(5L, row.tenantId)
        assertEquals(11L, row.agentId)
        assertEquals("read_file", row.toolName)
        assertEquals(1_000L, row.duration)
    }

    @Test
    @DisplayName("a session meta with no tenant records the call unattributed")
    fun emitKeepsAnUnattributedCallUnattributed() {
        adaptor.emit(info(tenantId = null))

        assertEquals(null, inserted.single().tenantId)
    }
}
