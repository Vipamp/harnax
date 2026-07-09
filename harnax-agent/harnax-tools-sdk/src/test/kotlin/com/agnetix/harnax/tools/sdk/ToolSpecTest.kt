package com.agnetix.harnax.tools.sdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ToolSpec Unit Tests
 *
 * Verifies ToolSpec data class construction, default values, and equality.
 *
 * @author agnetix
 * @since 2026-07-10
 */
class ToolSpecTest {

    @Nested
    @DisplayName("Default Values Tests")
    inner class DefaultValuesTests {

        @Test
        @DisplayName("should have default toolName as empty string")
        fun `default toolName should be empty string`() {
            val spec = ToolSpec(toolId = 1L)
            assertEquals("", spec.toolName)
        }

        @Test
        @DisplayName("should have default skipIfMissing as true")
        fun `default skipIfMissing should be true`() {
            val spec = ToolSpec(toolId = 1L)
            assertTrue(spec.skipIfMissing)
        }

        @Test
        @DisplayName("should have default needConfirm as false")
        fun `default needConfirm should be false`() {
            val spec = ToolSpec(toolId = 1L)
            assertFalse(spec.needConfirm)
        }
    }

    @Nested
    @DisplayName("Custom Values Tests")
    inner class CustomValuesTests {

        @Test
        @DisplayName("should accept all custom values")
        fun `should accept all custom values`() {
            val spec = ToolSpec(
                toolId = 42L,
                toolName = "my-tool",
                skipIfMissing = false,
                needConfirm = true,
            )
            assertEquals(42L, spec.toolId)
            assertEquals("my-tool", spec.toolName)
            assertFalse(spec.skipIfMissing)
            assertTrue(spec.needConfirm)
        }

        @Test
        @DisplayName("should support structural equality")
        fun `should support structural equality`() {
            val a = ToolSpec(toolId = 1L, toolName = "t", skipIfMissing = false, needConfirm = true)
            val b = ToolSpec(toolId = 1L, toolName = "t", skipIfMissing = false, needConfirm = true)
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
        }

        @Test
        @DisplayName("should support copy with overrides")
        fun `should support copy`() {
            val original = ToolSpec(toolId = 1L, toolName = "original")
            val copied = original.copy(toolName = "copied", needConfirm = true)
            assertEquals(1L, copied.toolId)
            assertEquals("copied", copied.toolName)
            assertTrue(copied.needConfirm)
            assertTrue(copied.skipIfMissing) // default preserved
        }
    }
}
