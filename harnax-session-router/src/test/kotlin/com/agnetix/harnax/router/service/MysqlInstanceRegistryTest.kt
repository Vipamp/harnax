package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.AgentInstanceMapper
import com.agnetix.harnax.router.service.impl.MysqlInstanceRegistry
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
        registry = MysqlInstanceRegistry(mapper, 30000)
    }

    @Test
    fun `registerInstance inserts new instance when not exists`() {
        `when`(mapper.selectByInstanceId("inst-1")).thenReturn(null)
        `when`(mapper.insert(any(AgentInstance::class.java))).thenReturn(1)

        registry.registerInstance("inst-1", "localhost", 8082)

        verify(mapper).insert(argThat {
            instanceId == "inst-1" && host == "localhost" && port == 8082 && status == "UP" && active == 1
        })
        verify(mapper, never()).updateHeartbeat(anyString(), any(), anyString())
    }

    @Test
    fun `registerInstance updates existing instance when already registered`() {
        val existing = AgentInstance().apply {
            instanceId = "inst-1"; host = "old-host"; port = 8080; status = "DOWN"
        }
        `when`(mapper.selectByInstanceId("inst-1")).thenReturn(existing)
        `when`(mapper.updateHeartbeat(anyString(), any(), anyString())).thenReturn(1)

        registry.registerInstance("inst-1", "new-host", 8082)

        verify(mapper, never()).insert(any())
        verify(mapper).updateHeartbeat(eq("inst-1"), any(), eq("UP"))
    }

    @Test
    fun `unregisterInstance calls deleteByInstanceId`() {
        `when`(mapper.deleteByInstanceId("inst-1")).thenReturn(1)

        registry.unregisterInstance("inst-1")

        verify(mapper).deleteByInstanceId("inst-1")
    }

    @Test
    fun `refreshHeartbeat updates heartbeat`() {
        `when`(mapper.updateHeartbeat(eq("inst-1"), any(), eq("UP"))).thenReturn(1)

        registry.refreshHeartbeat("inst-1")

        verify(mapper).updateHeartbeat(eq("inst-1"), any(), eq("UP"))
    }

    @Test
    fun `refreshHeartbeat logs warning when instance not found`() {
        `when`(mapper.updateHeartbeat(eq("inst-1"), any(), eq("UP"))).thenReturn(0)

        registry.refreshHeartbeat("inst-1")

        verify(mapper).updateHeartbeat(eq("inst-1"), any(), eq("UP"))
    }

    @Test
    fun `getHealthyInstances filters by heartbeat timeout`() {
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"; status = "UP"; active = 1; lastHeartbeat = LocalDateTime.now()
        }
        val stale = AgentInstance().apply {
            instanceId = "inst-2"; status = "UP"; active = 1; lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(mapper.selectHealthyInstances()).thenReturn(listOf(healthy, stale))

        val result = registry.getHealthyInstances()

        assertEquals(1, result.size)
        assertEquals("inst-1", result[0].instanceId)
    }

    @Test
    fun `getHealthyInstances returns empty when no instances`() {
        `when`(mapper.selectHealthyInstances()).thenReturn(emptyList())

        val result = registry.getHealthyInstances()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAllActiveInstances delegates to mapper`() {
        val instances = listOf(AgentInstance().apply { instanceId = "inst-1" })
        `when`(mapper.selectAllInstances()).thenReturn(instances)

        val result = registry.getAllActiveInstances()

        assertEquals(1, result.size)
        verify(mapper).selectAllInstances()
    }

    @Test
    fun `getInstance returns instance when found`() {
        val instance = AgentInstance().apply { instanceId = "inst-1" }
        `when`(mapper.selectByInstanceId("inst-1")).thenReturn(instance)

        val result = registry.getInstance("inst-1")

        assertNotNull(result)
        assertEquals("inst-1", result?.instanceId)
    }

    @Test
    fun `getInstance returns null when not found`() {
        `when`(mapper.selectByInstanceId("inst-1")).thenReturn(null)

        assertNull(registry.getInstance("inst-1"))
    }

    @Test
    fun `markInstanceDown calls mapper`() {
        `when`(mapper.markAsDown("inst-1")).thenReturn(1)

        registry.markInstanceDown("inst-1")

        verify(mapper).markAsDown("inst-1")
    }
}
