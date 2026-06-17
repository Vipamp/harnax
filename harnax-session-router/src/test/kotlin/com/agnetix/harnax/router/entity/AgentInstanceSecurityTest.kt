package com.agnetix.harnax.router.entity

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentInstanceSecurityTest {

    @Test
    fun `should block loopback addresses`() {
        assertTrue(AgentInstance.isBlockedHost("127.0.0.1"))
        assertTrue(AgentInstance.isBlockedHost("127.0.0.2"))
        assertTrue(AgentInstance.isBlockedHost("127.255.255.255"))
        assertTrue(AgentInstance.isBlockedHost("0.0.0.0"))
        assertTrue(AgentInstance.isBlockedHost("::1"))
        assertTrue(AgentInstance.isBlockedHost("localhost"))
    }

    @Test
    fun `should block link-local addresses`() {
        assertTrue(AgentInstance.isBlockedHost("169.254.0.1"))
        assertTrue(AgentInstance.isBlockedHost("169.254.169.254"))
        assertTrue(AgentInstance.isBlockedHost("169.254.255.255"))
        assertTrue(AgentInstance.isBlockedHost("fe80::1"))
    }

    @Test
    fun `should allow RFC 1918 private network addresses`() {
        // Class A
        assertFalse(AgentInstance.isBlockedHost("10.0.0.1"))
        assertFalse(AgentInstance.isBlockedHost("10.255.255.255"))
        assertFalse(AgentInstance.isBlockedHost("10.10.10.10"))
        // Class B
        assertFalse(AgentInstance.isBlockedHost("172.16.0.1"))
        assertFalse(AgentInstance.isBlockedHost("172.31.255.255"))
        assertFalse(AgentInstance.isBlockedHost("172.20.10.5"))
        // Class C
        assertFalse(AgentInstance.isBlockedHost("192.168.0.1"))
        assertFalse(AgentInstance.isBlockedHost("192.168.255.255"))
        assertFalse(AgentInstance.isBlockedHost("192.168.1.100"))
    }

    @Test
    fun `should allow public IP addresses`() {
        assertFalse(AgentInstance.isBlockedHost("8.8.8.8"))
        assertFalse(AgentInstance.isBlockedHost("1.1.1.1"))
        assertFalse(AgentInstance.isBlockedHost("203.0.113.1"))
        assertFalse(AgentInstance.isBlockedHost("198.51.100.1"))
    }

    @Test
    fun `should block metadata endpoints`() {
        assertTrue(AgentInstance.isBlockedHost("metadata.google.internal"))
        assertTrue(AgentInstance.isBlockedHost("metadata.google"))
        assertTrue(AgentInstance.isBlockedHost("metadata"))
        assertTrue(AgentInstance.isBlockedHost("169.254.169.254"))
    }

    @Test
    fun `should allow internal and local domain names`() {
        assertFalse(AgentInstance.isBlockedHost("service.internal"))
        assertFalse(AgentInstance.isBlockedHost("db.local"))
    }

    @Test
    fun `should allow valid domain names`() {
        assertFalse(AgentInstance.isBlockedHost("agent-service.example.com"))
        assertFalse(AgentInstance.isBlockedHost("router.prod.svc.cluster.local"))
    }

    @Test
    fun `should handle invalid IP formats`() {
        assertTrue(AgentInstance.isBlockedHost("256.1.1.1")) // Invalid octet
        assertTrue(AgentInstance.isBlockedHost("1.2.3.999")) // Invalid octet
        assertTrue(AgentInstance.isBlockedHost("abc.def.ghi.jkl")) // Non-numeric
    }

    @Test
    fun `should handle whitespace and case variations`() {
        assertTrue(AgentInstance.isBlockedHost("  127.0.0.1  "))
        assertTrue(AgentInstance.isBlockedHost("LOCALHOST"))
        assertTrue(AgentInstance.isBlockedHost("  METADATA  "))
    }

    @Test
    fun `should allow typical production IPs`() {
        // Kubernetes pod IPs (private network, allowed for internal services)
        assertFalse(AgentInstance.isBlockedHost("10.244.0.5"))

        // Public cloud IPs
        assertFalse(AgentInstance.isBlockedHost("34.120.54.123"))
        assertFalse(AgentInstance.isBlockedHost("52.94.76.1"))

        // On-premise public IPs
        assertFalse(AgentInstance.isBlockedHost("203.0.113.50"))
    }
}
