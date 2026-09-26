package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.LogType
import com.agnetix.harnax.agent.adaptor.ProcessLog
import com.agnetix.harnax.entity.ProcessLogEntity
import com.agnetix.harnax.mapper.ProcessLogMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any

/**
 * Unit tests for ProcessLogAdaptorImpl.
 *
 * V50 gave `process_log` a tenant column, and this conversion is the only step that can still fill it.
 * A dropped field there leaves every later line unattributed without a single error in the log, so the
 * written row itself is the thing under test.
 */
class ProcessLogAdaptorImplTest {

    private val inserted = mutableListOf<ProcessLogEntity>()
    private lateinit var adaptor: ProcessLogAdaptorImpl

    @BeforeEach
    fun setUp() {
        val processLogMapper = mock(ProcessLogMapper::class.java)
        `when`(processLogMapper.insert(any())).thenAnswer { invocation ->
            invocation.getArgument<ProcessLogEntity>(0).let { inserted += it }
            1
        }
        adaptor = ProcessLogAdaptorImpl(processLogMapper)
    }

    @Test
    @DisplayName("the line carries the run's tenant")
    fun emitLogStoresTheRunTenant() {
        adaptor.emitLog(
            ProcessLog(
                agentId = 11L,
                agentName = "Ops",
                sessionId = "s-1",
                message = "tool started",
                type = LogType.INFO,
                timestamp = 1_700_000_000_000L,
                tenantId = 5L,
            ),
        )

        val row = inserted.single()
        assertEquals(5L, row.tenantId)
        assertEquals(11L, row.agentId)
        assertEquals("s-1", row.sessionId)
    }

    @Test
    @DisplayName("a run that was never attributed stays unattributed instead of landing in the default workspace")
    fun emitLogKeepsAnUnattributedLineUnattributed() {
        adaptor.emitLog(
            ProcessLog(
                agentId = 11L,
                agentName = "Ops",
                sessionId = "s-1",
                message = "tool started",
                type = LogType.ERROR,
                timestamp = 1_700_000_000_000L,
                tenantId = null,
            ),
        )

        assertEquals(null, inserted.single().tenantId)
    }
}
