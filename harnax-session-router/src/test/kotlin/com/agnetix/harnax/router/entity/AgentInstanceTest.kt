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
        this.instanceId = "inst-1"
        this.host = host
        this.port = port
        this.status = status
        this.lastHeartbeat = lastHeartbeat
        this.active = active
    }

    // ==================== isHealthy tests ====================

    @Test
    fun `isHealthy returns true when UP, active, and heartbeat recent`() {
        assertTrue(createInstance().isHealthy(30000))
    }

    @Test
    fun `isHealthy returns false when status is DOWN`() {
        assertFalse(createInstance(status = "DOWN").isHealthy(30000))
    }

    @Test
    fun `isHealthy returns false when status is DRAINING`() {
        assertFalse(createInstance(status = "DRAINING").isHealthy(30000))
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

    // ==================== isDraining tests ====================

    @Test
    fun `isDraining returns true when status is DRAINING and active`() {
        assertTrue(createInstance(status = "DRAINING", active = 1).isDraining())
    }

    @Test
    fun `isDraining returns false when status is DRAINING but inactive`() {
        assertFalse(createInstance(status = "DRAINING", active = 0).isDraining())
    }

    @Test
    fun `isDraining returns false when status is UP`() {
        assertFalse(createInstance(status = "UP").isDraining())
    }

    @Test
    fun `isDraining returns false when status is DOWN`() {
        assertFalse(createInstance(status = "DOWN").isDraining())
    }

    // ==================== getBaseUrl tests ====================

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

    // ==================== DRAINING exclusion from healthy ====================

    @Test
    fun `DRAINING instance is not healthy but is draining`() {
        val instance = createInstance(status = "DRAINING", active = 1)
        assertFalse(instance.isHealthy(30000))
        assertTrue(instance.isDraining())
    }

    // ==================== SSRF protection tests ====================

    @Test
    fun `isBlockedHost blocks loopback 127 dot addresses`() {
        assertTrue(AgentInstance.isBlockedHost("127.0.0.1"))
        assertTrue(AgentInstance.isBlockedHost("127.1.2.3"))
    }

    @Test
    fun `isBlockedHost blocks zero address`() {
        assertTrue(AgentInstance.isBlockedHost("0.0.0.0"))
    }

    @Test
    fun `isBlockedHost blocks link-local addresses`() {
        assertTrue(AgentInstance.isBlockedHost("169.254.169.254"))
        assertTrue(AgentInstance.isBlockedHost("169.254.1.1"))
    }

    @Test
    fun `isBlockedHost blocks localhost hostname`() {
        assertTrue(AgentInstance.isBlockedHost("localhost"))
        assertTrue(AgentInstance.isBlockedHost("LOCALHOST"))
    }

    @Test
    fun `isBlockedHost blocks cloud metadata endpoints`() {
        assertTrue(AgentInstance.isBlockedHost("metadata.google.internal"))
        assertTrue(AgentInstance.isBlockedHost("metadata.google"))
    }

    @Test
    fun `isBlockedHost blocks IPv6 loopback`() {
        assertTrue(AgentInstance.isBlockedHost("::1"))
    }

    @Test
    fun `isBlockedHost blocks IPv6 link-local`() {
        assertTrue(AgentInstance.isBlockedHost("fe80::1"))
        assertTrue(AgentInstance.isBlockedHost("FE80::abcd"))
    }

    @Test
    fun `isBlockedHost allows valid internal service addresses`() {
        assertFalse(AgentInstance.isBlockedHost("10.0.0.1"))
        assertFalse(AgentInstance.isBlockedHost("192.168.1.100"))
        assertFalse(AgentInstance.isBlockedHost("172.16.0.1"))
        assertFalse(AgentInstance.isBlockedHost("agent-service.prod.local"))
    }

    @Test
    fun `isBlockedHost handles whitespace`() {
        assertTrue(AgentInstance.isBlockedHost("  localhost  "))
        assertTrue(AgentInstance.isBlockedHost(" 127.0.0.1 "))
    }
}
