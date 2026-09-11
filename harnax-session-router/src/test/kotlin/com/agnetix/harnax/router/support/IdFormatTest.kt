package com.agnetix.harnax.router.support

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IdFormatTest {

    @Test
    fun `accepts the ids this system generates`() {
        assertTrue(IdFormat.isSessionId("web-550e8400-e29b-41d4-a716-446655440000"))
        assertTrue(IdFormat.isSessionId("mp-3f2a1b"))
        assertTrue(IdFormat.isSessionId("sess_2026-09-11.1"))
        assertTrue(IdFormat.isSessionId("agent:tenant:42"))
        assertTrue(IdFormat.isInstanceId("agent-service-1"))
        assertTrue(IdFormat.isInstanceId("10.0.0.1_8082"))
    }

    @Test
    fun `rejects what has meaning in a url, a redis key or a log line`() {
        listOf(
            "",
            " ",
            "../admin",
            "a/b",
            "a?b",
            "a#b",
            "a%00",
            "a\nb",
            "a\tb",
            "a\\b",
            "a\"b",
            "a{b}",
            "a".repeat(129),
        ).forEach {
            assertFalse(IdFormat.isSessionId(it), "expected sessionId to be rejected: ${it.take(20)}")
        }
    }

    @Test
    fun `a single dot is allowed and a double dot is not`() {
        assertTrue(IdFormat.isSessionId("session.v2"))
        assertFalse(IdFormat.isSessionId("session..v2"))
        assertFalse(IdFormat.isInstanceId("inst..1"))
    }

    @Test
    fun `an oversized instance id is rejected`() {
        assertFalse(IdFormat.isInstanceId("i".repeat(IdFormat.MAX_INSTANCE_ID_LENGTH + 1)))
        assertTrue(IdFormat.isInstanceId("i".repeat(IdFormat.MAX_INSTANCE_ID_LENGTH)))
    }

    @Test
    fun `require does not echo the rejected value`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            IdFormat.requireSessionId("../../secret")
        }
        assertFalse(e.message!!.contains(".."), "the message must not carry the value being rejected")
    }

    @Test
    fun `parseSessionIds trims and keeps order`() {
        assertEquals(listOf("a", "b", "c"), IdFormat.parseSessionIds("a, b ,c"))
    }

    @Test
    fun `parseSessionIds rejects the whole list when one element is malformed`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdFormat.parseSessionIds("ok,not/ok")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IdFormat.parseSessionIds(",,")
        }
    }
}
