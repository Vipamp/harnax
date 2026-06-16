package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.entity.SessionMapping
import com.agnetix.harnax.router.mapper.SessionMappingMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.LocalDateTime

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

    // ==================== bindSession ====================

    @Test
    fun `bindSession calls upsertBinding with correct params`() {
        `when`(mapper.upsertBinding(anyString(), anyString(), any(), any(LocalDateTime::class.java))).thenReturn(1)

        service.bindSession("session-1", "inst-1", 42L)

        verify(mapper).upsertBinding(eq("session-1"), eq("inst-1"), eq(42L), any(LocalDateTime::class.java))
    }

    @Test
    fun `bindSession with null agentId`() {
        `when`(mapper.upsertBinding(anyString(), anyString(), any(), any(LocalDateTime::class.java))).thenReturn(1)

        service.bindSession("session-1", "inst-1", null)

        verify(mapper).upsertBinding(eq("session-1"), eq("inst-1"), isNull(), any(LocalDateTime::class.java))
    }

    @Test
    fun `bindSession uses atomic upsert instead of select-then-insert`() {
        `when`(mapper.upsertBinding(anyString(), anyString(), any(), any(LocalDateTime::class.java))).thenReturn(1)

        service.bindSession("session-1", "inst-1")

        verify(mapper).upsertBinding(anyString(), anyString(), any(), any(LocalDateTime::class.java))
        verify(mapper, never()).insert(any())
        verify(mapper, never()).updateBinding(anyString(), anyString(), any(LocalDateTime::class.java))
    }

    // ==================== getInstanceId ====================

    @Test
    fun `getInstanceId returns instanceId when mapping exists`() {
        val mapping = SessionMapping().apply {
            sessionId = "session-1"
            instanceId = "inst-1"
            active = 1
        }
        `when`(mapper.selectBySessionId("session-1")).thenReturn(mapping)

        val result = service.getInstanceId("session-1")

        assertEquals("inst-1", result)
    }

    @Test
    fun `getInstanceId returns null when no mapping`() {
        `when`(mapper.selectBySessionId("session-1")).thenReturn(null)

        val result = service.getInstanceId("session-1")

        assertNull(result)
    }

    // ==================== unbindSession ====================

    @Test
    fun `unbindSession calls deleteBySessionId`() {
        `when`(mapper.deleteBySessionId("session-1")).thenReturn(1)

        service.unbindSession("session-1")

        verify(mapper).deleteBySessionId("session-1")
    }

    // ==================== refreshActiveTime ====================

    @Test
    fun `refreshActiveTime calls mapper with current time`() {
        `when`(mapper.refreshActiveTime(anyString(), any(LocalDateTime::class.java))).thenReturn(1)

        service.refreshActiveTime("session-1")

        verify(mapper).refreshActiveTime(eq("session-1"), any(LocalDateTime::class.java))
    }

    // ==================== rerouteSession ====================

    @Test
    fun `rerouteSession selects least loaded instance using batch count`() {
        val inst1 = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
        }
        val inst2 = AgentInstance().apply {
            instanceId = "inst-2"
            status = "UP"
            active = 1
        }
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(inst1, inst2))
        `when`(mapper.countSessionsByInstances(listOf("inst-1", "inst-2"))).thenReturn(
            listOf(
                mapOf("instance_id" to "inst-1", "cnt" to 10L),
                mapOf("instance_id" to "inst-2", "cnt" to 3L),
            ),
        )
        `when`(mapper.upsertBinding(anyString(), anyString(), any(), any(LocalDateTime::class.java))).thenReturn(1)

        val result = service.rerouteSession("session-1")

        assertEquals("inst-2", result)
        verify(mapper).countSessionsByInstances(listOf("inst-1", "inst-2"))
        verify(mapper).upsertBinding(eq("session-1"), eq("inst-2"), isNull(), any(LocalDateTime::class.java))
    }

    @Test
    fun `rerouteSession throws when no healthy instances`() {
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(emptyList())

        assertThrows(IllegalStateException::class.java) {
            service.rerouteSession("session-1")
        }
    }

    @Test
    fun `rerouteSession with single instance skips batch query`() {
        val inst1 = AgentInstance().apply { instanceId = "inst-1" }
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(inst1))
        `when`(mapper.upsertBinding(anyString(), anyString(), any(), any(LocalDateTime::class.java))).thenReturn(1)

        val result = service.rerouteSession("session-1")

        assertEquals("inst-1", result)
        verify(mapper, never()).countSessionsByInstances(anyList())
    }

    @Test
    fun `rerouteSession handles missing count gracefully`() {
        val inst1 = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
        }
        val inst2 = AgentInstance().apply {
            instanceId = "inst-2"
            status = "UP"
            active = 1
        }
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(inst1, inst2))
        `when`(mapper.countSessionsByInstances(listOf("inst-1", "inst-2"))).thenReturn(
            listOf(mapOf("instance_id" to "inst-1", "cnt" to 5L)),
        )
        `when`(mapper.upsertBinding(anyString(), anyString(), any(), any(LocalDateTime::class.java))).thenReturn(1)

        val result = service.rerouteSession("session-1")

        assertEquals("inst-2", result)
    }

    // ==================== rebindAllSessions ====================

    @Test
    fun `rebindAllSessions calls mapper and returns count`() {
        `when`(mapper.rebindSessions("old-inst", "new-inst")).thenReturn(5)

        val count = service.rebindAllSessions("old-inst", "new-inst")

        assertEquals(5, count)
        verify(mapper).rebindSessions("old-inst", "new-inst")
    }

    @Test
    fun `rebindAllSessions returns 0 when no sessions to rebind`() {
        `when`(mapper.rebindSessions("old-inst", "new-inst")).thenReturn(0)

        val count = service.rebindAllSessions("old-inst", "new-inst")

        assertEquals(0, count)
    }

    // ==================== unbindInstanceSessions ====================

    @Test
    fun `unbindInstanceSessions calls deleteByInstanceId and returns count`() {
        `when`(mapper.deleteByInstanceId("inst-1")).thenReturn(3)

        val count = service.unbindInstanceSessions("inst-1")

        assertEquals(3, count)
        verify(mapper).deleteByInstanceId("inst-1")
    }

    @Test
    fun `unbindInstanceSessions returns 0 when no sessions bound`() {
        `when`(mapper.deleteByInstanceId("inst-1")).thenReturn(0)

        val count = service.unbindInstanceSessions("inst-1")

        assertEquals(0, count)
    }
}
