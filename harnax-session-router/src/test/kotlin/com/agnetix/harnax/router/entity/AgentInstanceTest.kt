package com.agnetix.harnax.router.entity

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class AgentInstanceTest {

    private fun createInstance(
        status: String = "UP",
        active: Int = 1,
        lastHeartbeat: Instant = Instant.now(),
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
    fun `isHealthy treats a fresh DRAINING instance as alive`() {
        // The health checker marks instances DOWN when they stop heartbeating. If DRAINING were
        // "unhealthy", every graceful shutdown would be reported as a crash and its sessions moved.
        assertTrue(createInstance(status = "DRAINING").isHealthy(30000))
    }

    @Test
    fun `isAcceptingNewSessions is true only for a fresh UP instance`() {
        assertTrue(createInstance().isAcceptingNewSessions(30000))
        assertFalse(createInstance(status = "DRAINING").isAcceptingNewSessions(30000))
        assertFalse(createInstance(status = "DOWN").isAcceptingNewSessions(30000))
        assertFalse(createInstance(lastHeartbeat = Instant.now().minusSeconds(60)).isAcceptingNewSessions(30000))
    }

    @Test
    fun `isHealthy returns false when active is 0`() {
        assertFalse(createInstance(active = 0).isHealthy(30000))
    }

    @Test
    fun `isHealthy returns false when heartbeat is stale`() {
        assertFalse(createInstance(lastHeartbeat = Instant.now().minusSeconds(60)).isHealthy(30000))
    }

    @Test
    fun `isHealthy returns true when heartbeat is just within timeout`() {
        assertTrue(createInstance(lastHeartbeat = Instant.now().minusSeconds(29)).isHealthy(30000))
    }

    @Test
    fun `isHealthy converts ms to seconds correctly`() {
        val instance = createInstance(lastHeartbeat = Instant.now().minusSeconds(5))
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
    fun `DRAINING instance is alive but takes no new sessions`() {
        val instance = createInstance(status = "DRAINING", active = 1)
        assertTrue(instance.isHealthy(30000))
        assertFalse(instance.isAcceptingNewSessions(30000))
        assertTrue(instance.isDraining())
    }

    // ==================== parseHeartbeat tests ====================

    @Test
    fun `parseHeartbeat reads epoch millis`() {
        val millis = 1_700_000_000_123L
        assertEquals(Instant.ofEpochMilli(millis), AgentInstance.parseHeartbeat(millis.toString()))
    }

    @Test
    fun `parseHeartbeat still reads the legacy LocalDateTime format`() {
        // A rolling upgrade leaves old values in Redis; losing them would mark live instances DOWN.
        val legacy = LocalDateTime.of(2025, 1, 1, 12, 0, 0)
        val expected = legacy.atZone(ZoneId.systemDefault()).toInstant()
        assertEquals(expected, AgentInstance.parseHeartbeat(legacy.toString()))
    }

    @Test
    fun `parseHeartbeat returns null for unreadable values`() {
        assertNull(AgentInstance.parseHeartbeat("invalid-timestamp"))
        assertNull(AgentInstance.parseHeartbeat(""))
        assertNull(AgentInstance.parseHeartbeat(null))
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
