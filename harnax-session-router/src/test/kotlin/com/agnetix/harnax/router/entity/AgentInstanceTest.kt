package com.agnetix.harnax.router.entity

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class AgentInstanceTest {

    private fun createInstance(
        status: String = "UP",
        active: Int = 1,
        lastHeartbeat: LocalDateTime = LocalDateTime.now(),
        host: String = "localhost",
        port: Int = 8082,
    ): AgentInstance = AgentInstance().apply {
        this.id = 1
        this.instanceId = "inst-1"
        this.host = host
        this.port = port
        this.status = status
        this.lastHeartbeat = lastHeartbeat
        this.active = active
    }

    @Test
    fun `isHealthy returns true when UP, active, and heartbeat recent`() {
        assertTrue(createInstance().isHealthy(30000))
    }

    @Test
    fun `isHealthy returns false when status is DOWN`() {
        assertFalse(createInstance(status = "DOWN").isHealthy(30000))
    }

    @Test
    fun `isHealthy returns false when active is 0`() {
        assertFalse(createInstance(active = 0).isHealthy(30000))
    }

    @Test
    fun `isHealthy returns false when heartbeat is stale`() {
        assertFalse(createInstance(lastHeartbeat = LocalDateTime.now().minusSeconds(60)).isHealthy(30000))
    }

    @Test
    fun `isHealthy returns true when heartbeat is just within timeout`() {
        assertTrue(createInstance(lastHeartbeat = LocalDateTime.now().minusSeconds(29)).isHealthy(30000))
    }

    @Test
    fun `isHealthy converts ms to seconds correctly`() {
        val instance = createInstance(lastHeartbeat = LocalDateTime.now().minusSeconds(5))
        assertTrue(instance.isHealthy(10000))
        assertFalse(instance.isHealthy(3000))
    }

    @Test
    fun `getBaseUrl returns http host colon port`() {
        val instance = createInstance(host = "192.168.1.100", port = 8082)
        assertEquals("http://192.168.1.100:8082", instance.getBaseUrl())
    }

    @Test
    fun `getBaseUrl with localhost`() {
        val instance = createInstance(host = "localhost", port = 9090)
        assertEquals("http://localhost:9090", instance.getBaseUrl())
    }
}
