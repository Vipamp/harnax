package com.agnetix.harnax.harness.sandbox

import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.io.ByteArrayInputStream

/**
 * Unit tests for [KeepAliveSandboxManager].
 *
 * Uses mock [DockerCommandExecutor] and [SandboxFactory] to test sandbox lifecycle
 * logic without requiring a real Docker daemon.
 */
class KeepAliveSandboxManagerTest {

    private lateinit var dockerExecutor: DockerCommandExecutor
    private lateinit var sandboxFactory: SandboxFactory

    @BeforeEach
    fun setUp() {
        dockerExecutor = mock()
        sandboxFactory = mock()
    }

    /**
     * Creates a [KeepAliveSandboxManager] with mock dependencies and skipScanOnStartup=true.
     */
    private fun createManager(
        image: String = "python:3.11-slim",
        workspaceRoot: String = "/workspace",
        maxSize: Int = 100,
        maxIdleTimeMs: Long = 30 * 60 * 1000L,
    ): KeepAliveSandboxManager = KeepAliveSandboxManager(
        image = image,
        workspaceRoot = workspaceRoot,
        maxSize = maxSize,
        maxIdleTimeMs = maxIdleTimeMs,
        dockerExecutor = dockerExecutor,
        sandboxFactory = sandboxFactory,
        skipScanOnStartup = true,
    )

    /**
     * Creates a mock [DockerSandbox] with configurable state.
     */
    private fun createMockSandbox(
        snapshot: SandboxSnapshot? = null,
    ): DockerSandbox {
        val sandbox = mock<DockerSandbox>()
        val state = mock<DockerSandboxState>()
        whenever(sandbox.state).thenReturn(state)
        whenever(state.snapshot).thenReturn(snapshot)
        return sandbox
    }

    // ==================== A. getOrCreate Scenarios ====================

    @Nested
    inner class GetOrCreate {

        @Test
        fun `scenario0 - returns cached sandbox without Docker operations`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()

            // First call: create new sandbox
            whenever(dockerExecutor.execute(any()))
                .thenReturn(DockerCommandResult(1, "not found")) // inspect returns nothing
            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)

            val first = manager.getOrCreate("session-1", null, null)

            // Second call: should return cached
            val second = manager.getOrCreate("session-1", null, null)

            assertSame(first, second, "Should return the same cached sandbox instance")

            // Verify sandboxFactory was only called once
            verify(sandboxFactory, times(1)).create(any())
        }

        @Test
        fun `scenario1 - creates new container when none exists`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()

            // inspect returns nothing (no existing container)
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "Error: No such container"))

            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)

            val result = manager.getOrCreate("session-1", null, null)

            assertNotNull(result)
            verify(sandboxFactory).create(
                argThat {
                    isContainerOwned && !isWorkspaceRootReady
                },
            )
            verify(mockSandbox).start()
        }

        @Test
        fun `scenario2 - starts stopped container and attaches`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()

            // inspect returns existing stopped container
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(0, "abc123def456|false"))

            // docker start succeeds
            whenever(dockerExecutor.execute(argThat { contains("start") }))
                .thenReturn(DockerCommandResult(0, "agentscope-sandbox-session-1"))

            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)

            val result = manager.getOrCreate("session-1", null, null)

            assertNotNull(result)
            verify(dockerExecutor).execute(argThat { contains("start") })
            verify(sandboxFactory).create(
                argThat {
                    containerId == "abc123def456" && !isContainerOwned
                },
            )
        }

        @Test
        fun `scenario3 - attaches to running container without starting`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()

            // inspect returns existing running container
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(0, "abc123def456|true"))

            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)

            val result = manager.getOrCreate("session-1", null, null)

            assertNotNull(result)
            // Verify docker start was NOT called (container already running)
            verify(dockerExecutor, never()).execute(argThat { contains("start") })
            verify(sandboxFactory).create(
                argThat {
                    containerId == "abc123def456" && !isContainerOwned
                },
            )
        }

        @Test
        fun `attach fails then falls back to create new`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()
            val newSandbox = createMockSandbox()

            // inspect returns existing running container
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(0, "abc123def456|true"))

            // First sandbox.start() throws (attach fails), second succeeds (new create)
            whenever(sandboxFactory.create(any()))
                .thenReturn(mockSandbox) // for attach attempt
                .thenReturn(newSandbox) // for new creation
            doThrow(RuntimeException("Container conflict")).whenever(mockSandbox).start()

            // docker rm -f for cleanup
            whenever(dockerExecutor.execute(argThat { contains("rm") }))
                .thenReturn(DockerCommandResult(0, ""))

            val result = manager.getOrCreate("session-1", null, null)

            assertNotNull(result)
            // Verify docker rm -f was called to clean up
            verify(dockerExecutor).execute(argThat { contains("rm") && contains("-f") })
            // Verify factory was called twice (attach attempt + new create)
            verify(sandboxFactory, times(2)).create(any())
        }

        @Test
        fun `max capacity triggers eviction of oldest sandbox`() {
            val manager = createManager(maxSize = 2)
            val sandbox1 = createMockSandbox()
            val sandbox2 = createMockSandbox()
            val sandbox3 = createMockSandbox()

            // Setup: no existing containers
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "not found"))
            whenever(dockerExecutor.execute(argThat { contains("rm") }))
                .thenReturn(DockerCommandResult(0, ""))

            whenever(sandboxFactory.create(any()))
                .thenReturn(sandbox1)
                .thenReturn(sandbox2)
                .thenReturn(sandbox3)

            // Fill to capacity
            manager.getOrCreate("session-1", null, null)
            Thread.sleep(10) // Ensure different lastAccessTime
            manager.getOrCreate("session-2", null, null)

            // This should trigger eviction
            manager.getOrCreate("session-3", null, null)

            // Verify destroy was called (docker rm -f)
            verify(dockerExecutor, atLeast(1)).execute(argThat { contains("rm") && contains("-f") })
        }
    }

    // ==================== B. destroy Scenarios ====================

    @Nested
    inner class Destroy {

        @Test
        fun `in cache with snapshot - persists workspace before destroying`() {
            val manager = createManager()
            val mockSnapshot = mock<SandboxSnapshot>()
            whenever(mockSnapshot.isPersistenceEnabled).thenReturn(true)

            val mockSandbox = createMockSandbox(snapshot = mockSnapshot)
            whenever(mockSandbox.persistWorkspace()).thenReturn(ByteArrayInputStream(ByteArray(0)))

            // First create the sandbox
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "not found"))
            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)
            manager.getOrCreate("session-1", null, null)

            // Now destroy
            whenever(dockerExecutor.execute(argThat { contains("rm") }))
                .thenReturn(DockerCommandResult(0, ""))

            manager.destroy("session-1")

            // Verify snapshot was persisted
            verify(mockSandbox).persistWorkspace()
            verify(mockSnapshot).persist(any())
            // Verify close was called
            verify(mockSandbox).close()
            // Verify docker rm -f was called
            verify(dockerExecutor).execute(argThat { contains("rm") && contains("-f") && contains("agentscope-sandbox-session-1") })
        }

        @Test
        fun `in cache without snapshot - skips persist, closes and removes`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox(snapshot = null)

            // Create the sandbox first
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "not found"))
            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)
            manager.getOrCreate("session-1", null, null)

            // Destroy
            whenever(dockerExecutor.execute(argThat { contains("rm") }))
                .thenReturn(DockerCommandResult(0, ""))

            manager.destroy("session-1")

            // Verify persist was NOT called
            verify(mockSandbox, never()).persistWorkspace()
            // Verify close was called
            verify(mockSandbox).close()
            // Verify docker rm -f was called
            verify(dockerExecutor).execute(argThat { contains("rm") && contains("-f") })
        }

        @Test
        fun `not in cache - still calls docker rm -f`() {
            val manager = createManager()

            whenever(dockerExecutor.execute(any()))
                .thenReturn(DockerCommandResult(0, ""))

            // Destroy without creating first
            manager.destroy("session-never-created")

            // Verify docker rm -f was STILL called (key fix verification)
            verify(dockerExecutor).execute(argThat { contains("rm") && contains("-f") })
        }

        @Test
        fun `docker rm fails - no exception thrown`() {
            val manager = createManager()

            whenever(dockerExecutor.execute(any()))
                .thenReturn(DockerCommandResult(1, "Error: No such container"))

            assertDoesNotThrow { manager.destroy("session-1") }
        }

        @Test
        fun `close fails - still removes container`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox(snapshot = null)

            // Create sandbox
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "not found"))
            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)
            manager.getOrCreate("session-1", null, null)

            // close() throws
            doThrow(RuntimeException("close failed")).whenever(mockSandbox).close()

            whenever(dockerExecutor.execute(argThat { contains("rm") }))
                .thenReturn(DockerCommandResult(0, ""))

            manager.destroy("session-1")

            // Verify docker rm -f was still called despite close() failure
            verify(dockerExecutor).execute(argThat { contains("rm") && contains("-f") })
        }

        @Test
        fun `persist fails - continues with close and remove`() {
            val manager = createManager()
            val mockSnapshot = mock<SandboxSnapshot>()
            whenever(mockSnapshot.isPersistenceEnabled).thenReturn(true)

            val mockSandbox = createMockSandbox(snapshot = mockSnapshot)
            whenever(mockSandbox.persistWorkspace()).thenThrow(RuntimeException("persist failed"))

            // Create sandbox
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "not found"))
            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)
            manager.getOrCreate("session-1", null, null)

            whenever(dockerExecutor.execute(argThat { contains("rm") }))
                .thenReturn(DockerCommandResult(0, ""))

            manager.destroy("session-1")

            // Verify close and rm -f were called despite persist failure
            verify(mockSandbox).close()
            verify(dockerExecutor).execute(argThat { contains("rm") && contains("-f") })
        }
    }

    // ==================== C. scanAndRestore Scenarios ====================

    @Nested
    inner class ScanAndRestore {

        @Test
        fun `no containers - does nothing`() {
            val manager = createManager()

            whenever(dockerExecutor.execute(argThat { contains("ps") }))
                .thenReturn(DockerCommandResult(0, ""))

            manager.scanAndRestore()

            assertTrue(manager.getActiveSessions().isEmpty())
            verify(sandboxFactory, never()).create(any())
        }

        @Test
        fun `running containers - attaches without starting`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()

            whenever(dockerExecutor.execute(argThat { contains("ps") }))
                .thenReturn(DockerCommandResult(0, "abc123|agentscope-sandbox-session-1|running"))

            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)

            manager.scanAndRestore()

            assertTrue(manager.getActiveSessions().contains("session-1"))
            verify(dockerExecutor, never()).execute(argThat { contains("start") })
            verify(sandboxFactory).create(
                argThat {
                    containerId == "abc123" && !isContainerOwned
                },
            )
        }

        @Test
        fun `stopped containers - starts and attaches`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()

            whenever(dockerExecutor.execute(argThat { contains("ps") }))
                .thenReturn(DockerCommandResult(0, "abc123|agentscope-sandbox-session-1|exited"))

            whenever(dockerExecutor.execute(argThat { contains("start") }))
                .thenReturn(DockerCommandResult(0, ""))

            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)

            manager.scanAndRestore()

            assertTrue(manager.getActiveSessions().contains("session-1"))
            verify(dockerExecutor).execute(argThat { contains("start") })
        }

        @Test
        fun `mixed containers - handles each correctly`() {
            val manager = createManager()
            val sandbox1 = createMockSandbox()
            val sandbox2 = createMockSandbox()

            whenever(dockerExecutor.execute(argThat { contains("ps") }))
                .thenReturn(DockerCommandResult(0, "abc111|agentscope-sandbox-session-1|running\nabc222|agentscope-sandbox-session-2|exited"))

            whenever(dockerExecutor.execute(argThat { contains("start") }))
                .thenReturn(DockerCommandResult(0, ""))

            whenever(sandboxFactory.create(any()))
                .thenReturn(sandbox1)
                .thenReturn(sandbox2)

            manager.scanAndRestore()

            val sessions = manager.getActiveSessions()
            assertTrue(sessions.contains("session-1"))
            assertTrue(sessions.contains("session-2"))
            // Only session-2 should trigger docker start
            verify(dockerExecutor, times(1)).execute(argThat { contains("start") })
        }

        @Test
        fun `restore failure - increments failed count, others continue`() {
            val manager = createManager()
            val goodSandbox = createMockSandbox()

            whenever(dockerExecutor.execute(argThat { contains("ps") }))
                .thenReturn(DockerCommandResult(0, "abc111|agentscope-sandbox-session-1|running\nabc222|agentscope-sandbox-session-2|running"))

            // First sandbox.start() throws, second succeeds
            val failingSandbox = mock<DockerSandbox>()
            doThrow(RuntimeException("start failed")).whenever(failingSandbox).start()

            whenever(sandboxFactory.create(any()))
                .thenReturn(failingSandbox)
                .thenReturn(goodSandbox)

            manager.scanAndRestore()

            // Only session-2 should be restored
            assertTrue(manager.getActiveSessions().contains("session-2"))
            assertFalse(manager.getActiveSessions().contains("session-1"))
        }

        @Test
        fun `session already in cache - skips`() {
            val manager = createManager()
            val existingSandbox = createMockSandbox()
            val newSandbox = createMockSandbox()

            // Pre-populate cache
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "not found"))
            whenever(sandboxFactory.create(any())).thenReturn(existingSandbox)
            manager.getOrCreate("session-1", null, null)

            // scanAndRestore finds the same session
            whenever(dockerExecutor.execute(argThat { contains("ps") }))
                .thenReturn(DockerCommandResult(0, "abc123|agentscope-sandbox-session-1|running"))
            whenever(sandboxFactory.create(any())).thenReturn(newSandbox)

            manager.scanAndRestore()

            // Verify factory was only called once (for getOrCreate, not scanAndRestore)
            verify(sandboxFactory, times(1)).create(any())
        }
    }

    // ==================== D. attachToExisting Scenarios ====================

    @Nested
    inner class AttachToExisting {

        @Test
        fun `in cache - returns cached without Docker calls`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()

            // Create sandbox first
            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "not found"))
            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)
            manager.getOrCreate("session-1", null, null)

            // Reset mocks to verify no Docker calls
            reset(dockerExecutor)

            val result = manager.attachToExisting("session-1")

            assertSame(mockSandbox, result)
            verifyNoInteractions(dockerExecutor)
        }

        @Test
        fun `container running - attaches with containerId set`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()

            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(0, "abc123def456|true"))

            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)

            val result = manager.attachToExisting("session-1")

            assertNotNull(result)
            verify(sandboxFactory).create(
                argThat {
                    containerId == "abc123def456" && !isContainerOwned && isWorkspaceRootReady
                },
            )
            verify(dockerExecutor, never()).execute(argThat { contains("start") })
        }

        @Test
        fun `container stopped - starts and attaches`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()

            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(0, "abc123def456|false"))

            whenever(dockerExecutor.execute(argThat { contains("start") }))
                .thenReturn(DockerCommandResult(0, ""))

            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)

            val result = manager.attachToExisting("session-1")

            assertNotNull(result)
            verify(dockerExecutor).execute(argThat { contains("start") })
            verify(sandboxFactory).create(
                argThat {
                    containerId == "abc123def456" && !isContainerOwned
                },
            )
        }

        @Test
        fun `no container - returns null`() {
            val manager = createManager()

            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "Error: No such container"))

            val result = manager.attachToExisting("session-1")

            assertNull(result)
            verify(sandboxFactory, never()).create(any())
        }
    }

    // ==================== E. getSandbox / Helper Methods ====================

    @Nested
    inner class GetSandboxAndHelpers {

        @Test
        fun `getSandbox - cached returns it`() {
            val manager = createManager()
            val mockSandbox = createMockSandbox()

            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "not found"))
            whenever(sandboxFactory.create(any())).thenReturn(mockSandbox)

            manager.getOrCreate("session-1", null, null)

            val result = manager.getSandbox("session-1")
            assertSame(mockSandbox, result)
        }

        @Test
        fun `getSandbox - not cached returns null without Docker calls`() {
            val manager = createManager()

            val result = manager.getSandbox("nonexistent")

            assertNull(result)
            verifyNoInteractions(dockerExecutor)
        }

        @Test
        fun `getActiveSessions - returns all session IDs`() {
            val manager = createManager()
            val sandbox1 = createMockSandbox()
            val sandbox2 = createMockSandbox()

            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "not found"))
            whenever(sandboxFactory.create(any()))
                .thenReturn(sandbox1)
                .thenReturn(sandbox2)

            manager.getOrCreate("session-1", null, null)
            manager.getOrCreate("session-2", null, null)

            val sessions = manager.getActiveSessions()
            assertEquals(2, sessions.size)
            assertTrue(sessions.contains("session-1"))
            assertTrue(sessions.contains("session-2"))
        }

        @Test
        fun `destroyAll - destroys all sessions`() {
            val manager = createManager()
            val sandbox1 = createMockSandbox()
            val sandbox2 = createMockSandbox()

            whenever(dockerExecutor.execute(argThat { contains("inspect") }))
                .thenReturn(DockerCommandResult(1, "not found"))
            whenever(sandboxFactory.create(any()))
                .thenReturn(sandbox1)
                .thenReturn(sandbox2)
            whenever(dockerExecutor.execute(argThat { contains("rm") }))
                .thenReturn(DockerCommandResult(0, ""))

            manager.getOrCreate("session-1", null, null)
            manager.getOrCreate("session-2", null, null)

            manager.destroyAll()

            // Verify docker rm -f was called for both sessions
            verify(dockerExecutor, times(2)).execute(argThat { contains("rm") && contains("-f") })
            assertTrue(manager.getActiveSessions().isEmpty())
        }
    }
}
