package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.AgentInstanceMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.LocalDateTime

class MysqlInstanceRegistryTest {

    private lateinit var mapper: AgentInstanceMapper
    private lateinit var registry: MysqlInstanceRegistry

    @BeforeEach
    fun setUp() {
        mapper = mock(AgentInstanceMapper::class.java)
        registry = MysqlInstanceRegistry(mapper, 30000, 3)
    }

    // ==================== registerInstance ====================

    @Test
    fun `registerInstance calls upsertInstance with correct params`() {
        `when`(mapper.upsertInstance(anyString(), anyString(), anyInt(), any(LocalDateTime::class.java))).thenReturn(1)

        registry.registerInstance("inst-1", "10.0.0.1", 8082)

        verify(mapper).upsertInstance(eq("inst-1"), eq("10.0.0.1"), eq(8082), any(LocalDateTime::class.java))
    }

    @Test
    fun `registerInstance uses upsert for atomic registration`() {
        `when`(mapper.upsertInstance(anyString(), anyString(), anyInt(), any(LocalDateTime::class.java))).thenReturn(2)

        registry.registerInstance("inst-1", "10.0.0.1", 8082)

        verify(mapper, never()).insert(any())
        verify(mapper).upsertInstance(anyString(), anyString(), anyInt(), any(LocalDateTime::class.java))
    }

    // ==================== unregisterInstance ====================

    @Test
    fun `unregisterInstance calls deleteByInstanceId and invalidates cache`() {
        `when`(mapper.deleteByInstanceId("inst-1")).thenReturn(1)

        registry.unregisterInstance("inst-1")

        verify(mapper).deleteByInstanceId("inst-1")
    }

    // ==================== refreshHeartbeat ====================

    @Test
    fun `refreshHeartbeat calls updateHeartbeat with UP status`() {
        `when`(mapper.updateHeartbeat(anyString(), any(LocalDateTime::class.java), eq("UP"))).thenReturn(1)

        registry.refreshHeartbeat("inst-1")

        verify(mapper).updateHeartbeat(eq("inst-1"), any(LocalDateTime::class.java), eq("UP"))
    }

    @Test
    fun `refreshHeartbeat warns when instance not found`() {
        `when`(mapper.updateHeartbeat(anyString(), any(LocalDateTime::class.java), eq("UP"))).thenReturn(0)

        registry.refreshHeartbeat("non-existent")

        verify(mapper).updateHeartbeat(eq("non-existent"), any(LocalDateTime::class.java), eq("UP"))
    }

    // ==================== getHealthyInstances ====================

    @Test
    fun `getHealthyInstances filters by heartbeat timeout`() {
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        val stale = AgentInstance().apply {
            instanceId = "inst-2"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(mapper.selectHealthyInstances()).thenReturn(listOf(healthy, stale))

        val result = registry.getHealthyInstances()

        assertEquals(1, result.size)
        assertEquals("inst-1", result[0].instanceId)
    }

    @Test
    fun `getHealthyInstances uses cache on second call`() {
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        `when`(mapper.selectHealthyInstances()).thenReturn(listOf(healthy))

        val result1 = registry.getHealthyInstances()
        val result2 = registry.getHealthyInstances()

        assertEquals(result1, result2)
        verify(mapper, times(1)).selectHealthyInstances()
    }

    @Test
    fun `getHealthyInstances returns empty when no healthy instances`() {
        `when`(mapper.selectHealthyInstances()).thenReturn(emptyList())

        val result = registry.getHealthyInstances()

        assertTrue(result.isEmpty())
    }

    // ==================== getAllActiveInstances ====================

    @Test
    fun `getAllActiveInstances returns all active instances`() {
        val up = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
        }
        val draining = AgentInstance().apply {
            instanceId = "inst-2"
            status = "DRAINING"
            active = 1
        }
        `when`(mapper.selectAllInstances()).thenReturn(listOf(up, draining))

        val result = registry.getAllActiveInstances()

        assertEquals(2, result.size)
        verify(mapper).selectAllInstances()
    }

    // ==================== getInstance (with cache) ====================

    @Test
    fun `getInstance returns instance when found`() {
        val instance = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
        }
        `when`(mapper.selectByInstanceId("inst-1")).thenReturn(instance)

        val result = registry.getInstance("inst-1")

        assertNotNull(result)
        assertEquals("inst-1", result?.instanceId)
    }

    @Test
    fun `getInstance returns null when not found`() {
        `when`(mapper.selectByInstanceId("non-existent")).thenReturn(null)

        val result = registry.getInstance("non-existent")

        assertNull(result)
    }

    @Test
    fun `getInstance caches result on repeated calls`() {
        val instance = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
        }
        `when`(mapper.selectByInstanceId("inst-1")).thenReturn(instance)

        registry.getInstance("inst-1")
        registry.getInstance("inst-1")

        verify(mapper, times(1)).selectByInstanceId("inst-1")
    }

    @Test
    fun `getInstance cache is invalidated after registerInstance`() {
        val instance = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
        }
        `when`(mapper.selectByInstanceId("inst-1")).thenReturn(instance)
        `when`(mapper.upsertInstance(anyString(), anyString(), anyInt(), any(LocalDateTime::class.java))).thenReturn(1)

        registry.getInstance("inst-1")
        registry.registerInstance("inst-1", "10.0.0.2", 8083)
        registry.getInstance("inst-1")

        verify(mapper, times(2)).selectByInstanceId("inst-1")
    }

    // ==================== markInstanceDown ====================

    @Test
    fun `markInstanceDown returns rows affected`() {
        `when`(mapper.markAsDown("inst-1")).thenReturn(1)

        val rows = registry.markInstanceDown("inst-1")

        assertEquals(1, rows)
        verify(mapper).markAsDown("inst-1")
    }

    @Test
    fun `markInstanceDown returns 0 when already down`() {
        `when`(mapper.markAsDown("inst-1")).thenReturn(0)

        val rows = registry.markInstanceDown("inst-1")

        assertEquals(0, rows)
    }

    @Test
    fun `markInstanceDown invalidates cache`() {
        `when`(mapper.markAsDown("inst-1")).thenReturn(1)
        `when`(mapper.selectHealthyInstances()).thenReturn(emptyList())

        registry.markInstanceDown("inst-1")
        registry.getHealthyInstances()

        verify(mapper).selectHealthyInstances()
    }

    // ==================== markAsDraining ====================

    @Test
    fun `markAsDraining updates heartbeat with DRAINING status`() {
        `when`(mapper.updateHeartbeat(anyString(), any(LocalDateTime::class.java), eq("DRAINING"))).thenReturn(1)

        registry.markAsDraining("inst-1")

        verify(mapper).updateHeartbeat(eq("inst-1"), any(LocalDateTime::class.java), eq("DRAINING"))
    }

    @Test
    fun `markAsDraining invalidates cache`() {
        `when`(mapper.updateHeartbeat(anyString(), any(LocalDateTime::class.java), eq("DRAINING"))).thenReturn(1)
        `when`(mapper.selectHealthyInstances()).thenReturn(emptyList())

        registry.markAsDraining("inst-1")
        registry.getHealthyInstances()

        verify(mapper).selectHealthyInstances()
    }

    // ==================== cache throttle ====================

    @Test
    fun `throttledInvalidateCache only invalidates once per throttle period`() {
        `when`(mapper.upsertInstance(anyString(), anyString(), anyInt(), any(LocalDateTime::class.java))).thenReturn(1)
        `when`(mapper.selectHealthyInstances()).thenReturn(emptyList())

        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.registerInstance("inst-2", "10.0.0.2", 8083)

        registry.getHealthyInstances()
        verify(mapper, times(1)).selectHealthyInstances()
    }
}
