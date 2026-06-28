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
                KeepAliveSandboxManager(image = "", workspaceRoot = "/workspace", skipScanOnStartup = true)
            }
            assertTrue(exception.message?.contains("image") == true)
        }

        @Test
        fun `constructor rejects blank workspaceRoot`() {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                KeepAliveSandboxManager(image = "python:3.11", workspaceRoot = "", skipScanOnStartup = true)
            }
            assertTrue(exception.message?.contains("workspaceRoot") == true)
        }

        @Test
        fun `constructor accepts valid parameters`() {
            assertDoesNotThrow {
                KeepAliveSandboxManager(image = "python:3.11-slim", workspaceRoot = "/workspace", skipScanOnStartup = true)
            }
        }

        @Test
        fun `constructor rejects whitespace-only image`() {
            assertThrows(IllegalArgumentException::class.java) {
                KeepAliveSandboxManager(image = "   ", workspaceRoot = "/workspace", skipScanOnStartup = true)
            }
        }

        @Test
        fun `constructor rejects zero maxSize`() {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                KeepAliveSandboxManager(image = "python:3.11", workspaceRoot = "/workspace", maxSize = 0, skipScanOnStartup = true)
            }
            assertTrue(exception.message?.contains("maxSize") == true)
        }

        @Test
        fun `constructor rejects negative maxSize`() {
            assertThrows(IllegalArgumentException::class.java) {
                KeepAliveSandboxManager(image = "python:3.11", workspaceRoot = "/workspace", maxSize = -1, skipScanOnStartup = true)
            }
        }

        @Test
        fun `constructor rejects zero maxIdleTimeMs`() {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                KeepAliveSandboxManager(image = "python:3.11", workspaceRoot = "/workspace", maxIdleTimeMs = 0, skipScanOnStartup = true)
            }
            assertTrue(exception.message?.contains("maxIdleTimeMs") == true)
        }

        @Test
        fun `constructor rejects negative maxIdleTimeMs`() {
            assertThrows(IllegalArgumentException::class.java) {
                KeepAliveSandboxManager(image = "python:3.11", workspaceRoot = "/workspace", maxIdleTimeMs = -1, skipScanOnStartup = true)
            }
        }

        @Test
        fun `constructor with custom executor and factory`() {
            val executor = DockerCommandExecutor { DockerCommandResult(0, "") }
            val factory = SandboxFactory { throw UnsupportedOperationException("test") }

            assertDoesNotThrow {
                KeepAliveSandboxManager(
                    image = "python:3.11",
                    workspaceRoot = "/workspace",
                    dockerExecutor = executor,
                    sandboxFactory = factory,
                    skipScanOnStartup = true,
                )
            }
        }
    }

    // ==================== destroy (no sandbox) ====================

    @Nested
    inner class Destroy {
        private lateinit var manager: KeepAliveSandboxManager
        private lateinit var mockExecutor: DockerCommandExecutor

        @BeforeEach
        fun setUp() {
            mockExecutor = DockerCommandExecutor { DockerCommandResult(0, "") }
            manager = KeepAliveSandboxManager(
                image = "python:3.11-slim",
                workspaceRoot = "/workspace",
                dockerExecutor = mockExecutor,
                sandboxFactory = SandboxFactory { throw UnsupportedOperationException("test") },
                skipScanOnStartup = true,
            )
        }

        @Test
        fun `destroy on non-existent session is safe`() {
            assertDoesNotThrow { manager.destroy("nonexistent-session") }
        }

        @Test
        fun `destroyAll on empty manager is safe`() {
            assertDoesNotThrow { manager.destroyAll() }
        }

        @Test
        fun `getSandbox on empty manager returns null`() {
            assertNull(manager.getSandbox("any-session"))
        }

        @Test
        fun `getActiveSessions on empty manager returns empty set`() {
            assertTrue(manager.getActiveSessions().isEmpty())
        }

        @Test
        fun `attachToExisting with no container returns null`() {
            val executor = DockerCommandExecutor { DockerCommandResult(1, "Error: No such container") }
            val mgr = KeepAliveSandboxManager(
                image = "python:3.11-slim",
                workspaceRoot = "/workspace",
                dockerExecutor = executor,
                sandboxFactory = SandboxFactory { throw UnsupportedOperationException("test") },
                skipScanOnStartup = true,
            )

            val result = mgr.attachToExisting("nonexistent")
            assertNull(result)
        }
    }

    // ==================== DockerCommandResult ====================

    @Nested
    inner class DockerCommandResultTest {
        @Test
        fun `data class equality`() {
            val r1 = DockerCommandResult(0, "output")
            val r2 = DockerCommandResult(0, "output")
            assertEquals(r1, r2)
        }

        @Test
        fun `data class inequality`() {
            val r1 = DockerCommandResult(0, "output1")
            val r2 = DockerCommandResult(0, "output2")
            assertNotEquals(r1, r2)
        }
    }
}
