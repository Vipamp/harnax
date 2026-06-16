package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.entity.SessionMapping
import com.agnetix.harnax.router.mapper.SessionMappingMapper
import com.agnetix.harnax.router.service.impl.MysqlSessionMappingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class MysqlSessionMappingServiceTest {

    private lateinit var mapper: SessionMappingMapper
    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var service: MysqlSessionMappingService

    @BeforeEach
    fun setUp() {
        mapper = mock(SessionMappingMapper::class.java)
        instanceRegistry = mock(InstanceRegistry::class.java)
        service = MysqlSessionMappingService(mapper, instanceRegistry)
    }

    @Test
    fun `bindSession inserts new mapping when not exists`() {
        `when`(mapper.selectBySessionId("session-1")).thenReturn(null)
        `when`(mapper.insert(any(SessionMapping::class.java))).thenReturn(1)

        service.bindSession("session-1", "inst-1", 100L)

        verify(mapper).insert(argThat {
            sessionId == "session-1" && instanceId == "inst-1" && agentId == 100L && active == 1
        })
        verify(mapper, never()).updateBinding(anyString(), anyString(), any())
    }

    @Test
    fun `bindSession updates existing mapping when already bound`() {
        val existing = SessionMapping().apply { sessionId = "session-1"; instanceId = "old-inst" }
        `when`(mapper.selectBySessionId("session-1")).thenReturn(existing)
        `when`(mapper.updateBinding(anyString(), anyString(), any())).thenReturn(1)

        service.bindSession("session-1", "inst-2")

        verify(mapper, never()).insert(any())
        verify(mapper).updateBinding(eq("session-1"), eq("inst-2"), any())
    }

    @Test
    fun `getInstanceId returns instanceId when mapping exists`() {
        val mapping = SessionMapping().apply { sessionId = "session-1"; instanceId = "inst-1" }
        `when`(mapper.selectBySessionId("session-1")).thenReturn(mapping)

        assertEquals("inst-1", service.getInstanceId("session-1"))
    }

    @Test
    fun `getInstanceId returns null when mapping not exists`() {
        `when`(mapper.selectBySessionId("session-1")).thenReturn(null)

        assertNull(service.getInstanceId("session-1"))
    }

    @Test
    fun `unbindSession calls deleteBySessionId`() {
        `when`(mapper.deleteBySessionId("session-1")).thenReturn(1)

        service.unbindSession("session-1")

        verify(mapper).deleteBySessionId("session-1")
    }

    @Test
    fun `refreshActiveTime calls mapper`() {
        `when`(mapper.refreshActiveTime(eq("session-1"), any())).thenReturn(1)

        service.refreshActiveTime("session-1")

        verify(mapper).refreshActiveTime(eq("session-1"), any())
    }

    @Test
    fun `rerouteSession selects healthy instance and binds`() {
        val inst1 = AgentInstance().apply { instanceId = "inst-1" }
        val inst2 = AgentInstance().apply { instanceId = "inst-2" }
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(inst1, inst2))
        `when`(mapper.selectBySessionId("session-1")).thenReturn(null)
        `when`(mapper.insert(any(SessionMapping::class.java))).thenReturn(1)
        `when`(mapper.selectByInstanceId("inst-1")).thenReturn(emptyList())
        `when`(mapper.selectByInstanceId("inst-2")).thenReturn(listOf(SessionMapping()))

        val result = service.rerouteSession("session-1")

        assertEquals("inst-1", result)
        verify(mapper).insert(argThat { instanceId == "inst-1" })
    }

    @Test
    fun `rerouteSession throws when no healthy instances`() {
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(emptyList())

        assertThrows(IllegalStateException::class.java) {
            service.rerouteSession("session-1")
        }
    }

    @Test
    fun `rebindAllSessions delegates to mapper`() {
        `when`(mapper.rebindSessions("old-inst", "new-inst")).thenReturn(5)

        val count = service.rebindAllSessions("old-inst", "new-inst")

        assertEquals(5, count)
        verify(mapper).rebindSessions("old-inst", "new-inst")
    }
}
