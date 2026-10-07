package com.agnetix.harnax.tools.sdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ToolCallContext Unit Tests
 *
 * Verifies the data class contracts for UserIdentifier,
 * including equality, copy, and ToolCallContext interface implementation.
 *
 * @author agnetix
 * @since 2026-07-10
 */
class ToolCallContextTest {

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
