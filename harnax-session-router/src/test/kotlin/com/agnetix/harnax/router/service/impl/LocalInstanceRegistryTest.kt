package com.agnetix.harnax.router.service.impl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocalInstanceRegistryTest {

    private lateinit var registry: LocalInstanceRegistry

    @BeforeEach
    fun setUp() {
        registry = LocalInstanceRegistry(30000)
    }

    @Test
    fun `registerInstance adds instance to registry`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)

        val instance = registry.getInstance("inst-1")
        assertNotNull(instance)
        assertEquals("inst-1", instance?.instanceId)
        assertEquals("10.0.0.1", instance?.host)
        assertEquals(8082, instance?.port)
        assertEquals("UP", instance?.status)
    }

    @Test
    fun `unregisterInstance removes instance from registry`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.unregisterInstance("inst-1")

        assertNull(registry.getInstance("inst-1"))
    }

    @Test
    fun `refreshHeartbeat updates lastHeartbeat`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        val before = registry.getInstance("inst-1")?.lastHeartbeat

        Thread.sleep(50)
        registry.refreshHeartbeat("inst-1")

        val after = registry.getInstance("inst-1")?.lastHeartbeat
        assertTrue(after?.isAfter(before) ?: false)
    }

    @Test
    fun `refreshHeartbeat returns false for unknown instance`() {
        assertFalse(registry.refreshHeartbeat("non-existent"))
    }

    @Test
    fun `refreshHeartbeat keeps a draining instance draining`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.markAsDraining("inst-1")

        assertTrue(registry.refreshHeartbeat("inst-1"))

        assertEquals("DRAINING", registry.getInstance("inst-1")?.status)
        assertTrue(registry.getHealthyInstances().isEmpty())
    }

    @Test
    fun `getHealthyInstances returns only healthy instances`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.registerInstance("inst-2", "10.0.0.2", 8083)

        registry.markInstanceDown("inst-2")

        val healthy = registry.getHealthyInstances()
        assertEquals(1, healthy.size)
        assertEquals("inst-1", healthy[0].instanceId)
    }

    @Test
    fun `getHealthyInstances filters stale heartbeat`() {
        val shortTimeoutRegistry = LocalInstanceRegistry(1000)
        shortTimeoutRegistry.registerInstance("inst-1", "10.0.0.1", 8082)

        Thread.sleep(1500)

        val healthy = shortTimeoutRegistry.getHealthyInstances()
        assertTrue(healthy.isEmpty())
    }

    @Test
    fun `getAllActiveInstances returns all active instances regardless of status`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.registerInstance("inst-2", "10.0.0.2", 8083)
        registry.markAsDraining("inst-2")

        val all = registry.getAllActiveInstances()
        assertEquals(2, all.size)
    }

    @Test
    fun `getInstance returns null for inactive instance`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.unregisterInstance("inst-1")

        assertNull(registry.getInstance("inst-1"))
    }

    @Test
    fun `markInstanceDown returns 1 when status changes`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)

        val result = registry.markInstanceDown("inst-1")
        assertEquals(1, result)
    }

    @Test
    fun `markInstanceDown returns 0 when already DOWN`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.markInstanceDown("inst-1")

        val result = registry.markInstanceDown("inst-1")
        assertEquals(0, result)
    }

    @Test
    fun `markInstanceDown returns 0 for unknown instance`() {
        val result = registry.markInstanceDown("non-existent")
        assertEquals(0, result)
    }

    @Test
    fun `markAsDraining changes status to DRAINING`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.markAsDraining("inst-1")

        val instance = registry.getInstance("inst-1")
        assertEquals("DRAINING", instance?.status)
        assertTrue(instance?.isDraining() ?: false)
    }

    @Test
    fun `markAsDraining returns false for unknown instance`() {
        assertFalse(registry.markAsDraining("non-existent"))
    }

    @Test
    fun `markInstanceDown keeps the registration so a heartbeat can recover it`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.markInstanceDown("inst-1")

        assertNotNull(registry.getInstance("inst-1"))
        assertTrue(registry.refreshHeartbeat("inst-1"))
        assertEquals("UP", registry.getInstance("inst-1")?.status)
        assertEquals(1, registry.getHealthyInstances().size)
    }

    @Test
    fun `registerInstance overwrites existing instance`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.registerInstance("inst-1", "10.0.0.2", 8083)

        val instance = registry.getInstance("inst-1")
        assertEquals("10.0.0.2", instance?.host)
        assertEquals(8083, instance?.port)
    }

    @Test
    fun `DRRAINING instance is not healthy but is active`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.markAsDraining("inst-1")

        val healthy = registry.getHealthyInstances()
        assertTrue(healthy.isEmpty())

        val allActive = registry.getAllActiveInstances()
        assertEquals(1, allActive.size)
    }
}
