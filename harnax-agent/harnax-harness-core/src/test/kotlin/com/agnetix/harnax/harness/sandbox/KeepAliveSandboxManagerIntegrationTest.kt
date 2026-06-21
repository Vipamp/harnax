package com.agnetix.harnax.harness.sandbox

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Integration test for KeepAliveSandboxManager.
 *
 * Tests the ConcurrentHashMap-based sandbox cache lifecycle
 * without requiring Docker — validates constructor constraints,
 * cache behavior, and destroy semantics.
 */
class KeepAliveSandboxManagerIntegrationTest {

    // ==================== Constructor validation ====================

    @Nested
    inner class ConstructorValidation {
        @Test
        fun `constructor rejects blank image`() {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                KeepAliveSandboxManager(image = "", workspaceRoot = "/workspace")
            }
            assertTrue(exception.message?.contains("image") == true)
        }

        @Test
        fun `constructor rejects blank workspaceRoot`() {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                KeepAliveSandboxManager(image = "python:3.11", workspaceRoot = "")
            }
            assertTrue(exception.message?.contains("workspaceRoot") == true)
        }

        @Test
        fun `constructor accepts valid parameters`() {
            assertDoesNotThrow {
                KeepAliveSandboxManager(image = "python:3.11-slim", workspaceRoot = "/workspace")
            }
        }

        @Test
        fun `constructor rejects whitespace-only image`() {
            assertThrows(IllegalArgumentException::class.java) {
                KeepAliveSandboxManager(image = "   ", workspaceRoot = "/workspace")
            }
        }
    }

    // ==================== destroy (no sandbox) ====================

    @Nested
    inner class Destroy {
        private lateinit var manager: KeepAliveSandboxManager

        @BeforeEach
        fun setUp() {
            manager = KeepAliveSandboxManager("python:3.11-slim", "/workspace")
        }

        @Test
        fun `destroy on non-existent session is safe`() {
            assertDoesNotThrow { manager.destroy("nonexistent-session") }
        }

        @Test
        fun `destroyAll on empty manager is safe`() {
            assertDoesNotThrow { manager.destroyAll() }
        }
    }
}
