package com.agnetix.harnax.tools.sdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ToolCallContext Unit Tests
 *
 * Verifies the data class contracts for SessionMetaContext and UserIdentifier,
 * including equality, copy, and ToolCallContext interface implementation.
 *
 * @author agnetix
 * @since 2026-07-10
 */
class ToolCallContextTest {

    @Nested
    @DisplayName("SessionMetaContext Tests")
    inner class SessionMetaContextTests {

        @Test
        @DisplayName("should implement ToolCallContext interface")
        fun `should implement ToolCallContext`() {
            val ctx = SessionMetaContext(agentId = 1L, sessionId = "s1")
            assertTrue(ctx is ToolCallContext)
        }

        @Test
        @DisplayName("should support structural equality")
        fun `should support structural equality`() {
            val a = SessionMetaContext(agentId = 1L, sessionId = "sess-001")
            val b = SessionMetaContext(agentId = 1L, sessionId = "sess-001")
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
        }

        @Test
        @DisplayName("should support inequality")
        fun `should support inequality`() {
            val a = SessionMetaContext(agentId = 1L, sessionId = "sess-001")
            val b = SessionMetaContext(agentId = 2L, sessionId = "sess-001")
            assertNotEquals(a, b)
        }

        @Test
        @DisplayName("should support copy")
        fun `should support copy`() {
            val original = SessionMetaContext(agentId = 1L, sessionId = "sess-001")
            val copied = original.copy(sessionId = "sess-002")
            assertEquals(1L, copied.agentId)
            assertEquals("sess-002", copied.sessionId)
        }

        @Test
        @DisplayName("toString should contain field values")
        fun `toString should contain field values`() {
            val ctx = SessionMetaContext(agentId = 10L, sessionId = "abc")
            val str = ctx.toString()
            assertTrue(str.contains("10"))
            assertTrue(str.contains("abc"))
        }
    }

    @Nested
    @DisplayName("UserIdentifier Tests")
    inner class UserIdentifierTests {

        @Test
        @DisplayName("should implement ToolCallContext interface")
        fun `should implement ToolCallContext`() {
            val uid = UserIdentifier(userId = 99L)
            assertTrue(uid is ToolCallContext)
        }

        @Test
        @DisplayName("should support structural equality")
        fun `should support structural equality`() {
            val a = UserIdentifier(userId = 42L)
            val b = UserIdentifier(userId = 42L)
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
        }

        @Test
        @DisplayName("should support copy")
        fun `should support copy`() {
            val original = UserIdentifier(userId = 42L)
            val copied = original.copy(userId = 100L)
            assertEquals(100L, copied.userId)
        }
    }
}
