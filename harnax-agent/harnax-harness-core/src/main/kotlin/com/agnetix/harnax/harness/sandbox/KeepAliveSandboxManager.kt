package com.agnetix.harnax.harness.sandbox

import io.agentscope.harness.agent.sandbox.WorkspaceSpec
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Docker sandbox containers that persist across agent calls.
 *
 * Uses the agentscope "external sandbox" (Priority 1 user-managed) mechanism:
 * when a [Sandbox] is provided via [SandboxContext.Builder.externalSandbox],
 * [io.agentscope.harness.agent.sandbox.SandboxManager.release] skips stop/shutdown,
 * keeping the container running for the next call.
 *
 * @param image Docker image for sandbox containers
 * @param workspaceRoot workspace root path inside the container
 * @param maxSize maximum number of concurrent sandboxes
 * @param maxIdleTimeMs maximum idle time before a sandbox is evicted
 * @param dockerExecutor Docker CLI command executor (injectable for testing)
 * @param sandboxFactory factory for creating DockerSandbox instances (injectable for testing)
 * @param snapshotSpec optional snapshot spec for workspace persistence; when provided,
 *   all restored/attached sandboxes will have snapshot enabled so that [persistAll] and
 *   [destroy] can persist the workspace before the container is removed.
 * @param skipScanOnStartup if true, skips the background scan on construction (for testing)
 */
class KeepAliveSandboxManager(
    private val image: String,
    private val workspaceRoot: String,
    private val maxSize: Int = 100,
    private val maxIdleTimeMs: Long = 30 * 60 * 1000L,
    private val dockerExecutor: DockerCommandExecutor = DefaultDockerCommandExecutor(),
    private val sandboxFactory: SandboxFactory = SandboxFactory { DockerSandbox(it) },
    private val snapshotSpec: SandboxSnapshotSpec? = null,
    private val skipScanOnStartup: Boolean = false,
) {

    init {
        require(image.isNotBlank()) {
            "Sandbox image must not be blank. Check harness.sandbox.image configuration."
        }
        require(workspaceRoot.isNotBlank()) {
            "Sandbox workspaceRoot must not be blank. Check harness.sandbox.workspace-root configuration."
        }
        require(maxSize > 0) { "maxSize must be positive" }
        require(maxIdleTimeMs > 0) { "maxIdleTimeMs must be positive" }

        // Scan and restore existing sandbox containers on startup (async to not block construction)
        if (!skipScanOnStartup) {
            Thread {
                try {
                    Thread.sleep(1000) // Give service time to fully start
                    scanAndRestore()
                } catch (e: Exception) {
                    log.error("[keepAlive] Failed to scan and restore sandboxes", e)
                }
            }.apply {
                name = "sandbox-scanner"
                isDaemon = true
                start()
            }
        }
    }

    private val log = LoggerFactory.getLogger(KeepAliveSandboxManager::class.java)

    private data class SandboxEntry(
        val sandbox: DockerSandbox,
        var lastAccessTime: Long = System.currentTimeMillis(),
    )

    private val sandboxes = ConcurrentHashMap<String, SandboxEntry>()

    /**
     * Scans Docker for existing sandbox containers and attaches to them.
     * This should be called during service startup to restore sandbox state.
     */
    fun scanAndRestore() {
        log.info("[keepAlive] Scanning Docker for existing sandbox containers...")
        return try {
            val result = dockerExecutor.execute(
                listOf("docker", "ps", "-a", "--filter", "name=agentscope-sandbox-", "--format", "{{.ID}}|{{.Names}}|{{.State}}"),
            )
            val output = result.output

            if (output.isBlank()) {
                log.info("[keepAlive] No existing sandbox containers found")
                return
            }

            val containerLines = output.lines().filter { it.isNotBlank() }
            log.info("[keepAlive] Found {} existing sandbox containers", containerLines.size)

            var restoredCount = 0
            var failedCount = 0

            for (line in containerLines) {
                val parts = line.split("|")
                if (parts.size < 3) continue
                val containerId = parts[0]
                val containerName = parts[1]
                val isRunning = parts[2] == "running"

                // Extract session ID from container name (format: agentscope-sandbox-{sessionId})
                val sessionId = containerName.removePrefix("agentscope-sandbox-")
                if (sessionId.isBlank()) continue

                try {
                    // If container is stopped, start it first
                    if (!isRunning) {
                        log.info("[keepAlive] Starting stopped container for session={}", sessionId)
                        dockerExecutor.execute(listOf("docker", "start", containerName))
                    }

                    // Attach to existing container
                    val state = DockerSandboxState()
                    state.setSessionId(sessionId)
                    state.setImage(image)
                    state.setWorkspaceRoot(workspaceRoot)
                    state.setContainerOwned(false)
                    state.setWorkspaceRootReady(true)
                    state.setWorkspaceSpec(WorkspaceSpec())
                    state.setContainerId(containerId)
                    if (snapshotSpec != null) {
                        state.setSnapshot(snapshotSpec.build(sessionId))
                    }

                    val sandbox = sandboxFactory.create(state)
                    sandbox.start()

                    // Use putIfAbsent to avoid race condition with getOrCreate():
                    // if another thread already added a sandbox for this session, discard ours.
                    val previous = sandboxes.putIfAbsent(sessionId, SandboxEntry(sandbox))
                    if (previous != null) {
                        log.debug("[keepAlive] Session {} already in memory after scan, discarding duplicate", sessionId)
                        try {
                            sandbox.close()
                        } catch (e: Exception) {
                            log.debug("[keepAlive] Failed to close duplicate sandbox for session={}", sessionId)
                        }
                        continue
                    }
                    restoredCount++
                    log.info("[keepAlive] Restored sandbox for session={}, containerId={}", sessionId, containerId)
                } catch (e: Exception) {
                    failedCount++
                    log.warn("[keepAlive] Failed to restore sandbox for session={}: {}", sessionId, e.message)
                }
            }

            log.info("[keepAlive] Scan complete: restored={}, failed={}, total={}", restoredCount, failedCount, containerLines.size)
        } catch (e: Exception) {
            log.error("[keepAlive] Failed to scan Docker containers", e)
        }
    }

    /**
     * Returns a running [DockerSandbox] for the given session, creating one if necessary.
     * The container stays alive across calls — it is only destroyed via [destroy].
     *
     * Logic:
     * 1. If sandbox is in memory -> return cached (no Docker operation)
     * 2. If Docker container exists for this session -> attach to it (start if stopped, attach if running)
     * 3. If no container exists -> create a new one
     *
     * @param sessionId session identifier, used as both the sandbox session ID and cache key
     * @param workspaceSpec workspace specification (projection, entries) for initial workspace init
     * @param snapshotSpec optional snapshot spec for workspace persistence on crash recovery
     */
    fun getOrCreate(
        sessionId: String,
        workspaceSpec: WorkspaceSpec?,
        snapshotSpec: SandboxSnapshotSpec?,
    ): DockerSandbox {
        // Cleanup idle sandboxes before creating new ones
        cleanupIdle()

        // Check capacity and evict BEFORE entering compute() to avoid modifying
        // the map during computation (ConcurrentHashMap.compute() contract violation)
        if (!sandboxes.containsKey(sessionId) && sandboxes.size >= maxSize) {
            log.warn("[keepAlive] Max capacity {} reached, cleaning up oldest sandbox", maxSize)
            evictOldest()
        }

        return sandboxes.compute(sessionId) { _, existing ->
            if (existing != null) {
                // Scenario 0: Already in memory -> return cached
                existing.lastAccessTime = System.currentTimeMillis()
                existing
            } else {
                // Scenario 2/3: Check if Docker container already exists for this session
                val existingContainerInfo = inspectExistingContainer(sessionId)
                if (existingContainerInfo != null) {
                    val (containerId, isRunning) = existingContainerInfo
                    log.info(
                        "[keepAlive] Found existing container for session={}, containerId={}, running={}, attaching",
                        sessionId,
                        containerId,
                        isRunning,
                    )

                    // If container is stopped, start it first (Scenario 2)
                    if (!isRunning) {
                        val startResult = dockerExecutor.execute(listOf("docker", "start", "agentscope-sandbox-$sessionId"))
                        if (startResult.exitCode != 0) {
                            log.warn("[keepAlive] Failed to start existing container for session={}, output: {}", sessionId, startResult.output)
                        } else {
                            log.info("[keepAlive] Started stopped container for session={}", sessionId)
                        }
                    }

                    // Attach to existing container (Scenario 2/3)
                    val state = DockerSandboxState()
                    state.setSessionId(sessionId)
                    state.setImage(image)
                    state.setWorkspaceRoot(workspaceRoot)
                    state.setContainerOwned(false)
                    state.setWorkspaceRootReady(true)
                    state.setWorkspaceSpec(workspaceSpec ?: WorkspaceSpec())
                    state.setContainerId(containerId)
                    if (snapshotSpec != null) {
                        state.setSnapshot(snapshotSpec.build(sessionId))
                    }

                    val sandbox = sandboxFactory.create(state)
                    try {
                        sandbox.start()
                        log.info("[keepAlive] Attached to existing container for session={}, containerId={}", sessionId, containerId)
                        return@compute SandboxEntry(sandbox)
                    } catch (e: Exception) {
                        log.warn("[keepAlive] Failed to attach to existing container for session={}, will remove and recreate", sessionId, e)
                        // Fall through to create new container after removing the conflicting one
                        try {
                            dockerExecutor.execute(listOf("docker", "rm", "-f", "agentscope-sandbox-$sessionId"))
                        } catch (removeEx: Exception) {
                            log.warn("[keepAlive] Failed to remove conflicting container", removeEx)
                        }
                    }
                }

                // Scenario 1: No existing container -> create new
                log.info("[keepAlive] Creating new sandbox for session={}, image={}, workspaceRoot={}", sessionId, image, workspaceRoot)
                val state = DockerSandboxState()
                state.setSessionId(sessionId)
                state.setImage(image)
                state.setWorkspaceRoot(workspaceRoot)
                state.setContainerOwned(true)
                state.setWorkspaceRootReady(false)
                if (workspaceSpec != null) {
                    state.setWorkspaceSpec(workspaceSpec)
                }
                if (snapshotSpec != null) {
                    state.setSnapshot(snapshotSpec.build(sessionId))
                }

                // Diagnostic + reflection fallback for image field
                val imageViaGetter = state.getImage()
                if (imageViaGetter == null) {
                    log.warn("[keepAlive] getImage() returned null after setImage('{}'), using reflection fallback", image)
                    try {
                        val field = DockerSandboxState::class.java.getDeclaredField("image")
                        field.trySetAccessible()
                        field.set(state, image)
                        log.info("[keepAlive] Reflection set image={}, getImage()={}", image, state.getImage())
                    } catch (e: Exception) {
                        log.error("[keepAlive] Reflection fallback failed", e)
                        throw RuntimeException("Cannot set DockerSandboxState.image", e)
                    }
                }

                val sandbox = sandboxFactory.create(state)
                try {
                    sandbox.start()
                    log.info("[keepAlive] Sandbox created and started for session={}, containerId={}", sessionId, state.getContainerId())
                } catch (e: Exception) {
                    log.error("[keepAlive] Failed to start new sandbox for session={}", sessionId, e)
                    throw RuntimeException("Failed to start keepAlive sandbox for session=$sessionId", e)
                }
                SandboxEntry(sandbox)
            }
        }!!.sandbox
    }

    /**
     * Inspects Docker for an existing container matching the session ID.
     * Returns (containerId, isRunning) pair, or null if no container exists.
     */
    private fun inspectExistingContainer(sessionId: String): Pair<String, Boolean>? {
        val containerName = "agentscope-sandbox-$sessionId"
        return try {
            val result = dockerExecutor.execute(
                listOf("docker", "inspect", "--format", "{{.Id}}|{{.State.Running}}", containerName),
            )
            val output = result.output

            if (output.contains("|")) {
                val parts = output.split("|")
                Pair(parts[0], parts[1] == "true")
            } else {
                null
            }
        } catch (e: Exception) {
            log.debug("[keepAlive] docker inspect failed for session={}: {}", sessionId, e.message)
            null
        }
    }

    /**
     * Stops and removes the sandbox container for the given session.
     * IMPORTANT: This always attempts to remove the container, regardless of containerOwned flag.
     * Even if the sandbox is not in memory cache, it will try to remove the Docker container.
     *
     * Before removing the container, if a snapshot is available, the workspace is persisted.
     */
    fun destroy(sessionId: String) {
        val containerName = "agentscope-sandbox-$sessionId"

        // Remove from memory cache if present
        val entry = sandboxes.remove(sessionId)

        // Step 1: Persist snapshot before destroying
        if (entry != null) {
            // Sandbox is in memory cache — persist directly
            try {
                val snapshot = entry.sandbox.state.snapshot
                if (snapshot != null && snapshot.isPersistenceEnabled) {
                    log.info("[keepAlive] Persisting workspace snapshot for session={} before destroy", sessionId)
                    entry.sandbox.persistWorkspace().use { archive ->
                        snapshot.persist(archive)
                    }
                    log.info("[keepAlive] Workspace snapshot persisted for session={}", sessionId)
                }
            } catch (e: Exception) {
                log.warn("[keepAlive] Failed to persist snapshot for session={}: {}", sessionId, e.message)
            }

            // Step 2: Close sandbox (may not remove container if containerOwned=false)
            try {
                entry.sandbox.close()
            } catch (e: Exception) {
                log.debug("[keepAlive] sandbox.close() failed for session={}, will force remove", sessionId)
            }
        } else if (snapshotSpec != null) {
            // Sandbox not in cache but container may still exist (e.g. service restarted,
            // scanAndRestore hasn't run yet). Try to attach, persist, then destroy.
            persistFromOrphanedContainer(sessionId)
        }

        // Step 3: IMPORTANT: Always force remove the container, regardless of containerOwned or cache state
        // This ensures STOP_SANDBOX command always works, even if sandbox was restored from scan
        try {
            val result = dockerExecutor.execute(listOf("docker", "rm", "-f", containerName))
            if (result.exitCode == 0) {
                log.info("[keepAlive] Sandbox destroyed for session={}", sessionId)
            } else {
                log.debug("[keepAlive] Container may not exist for session={}: {}", sessionId, result.output)
            }
        } catch (e: Exception) {
            log.warn("[keepAlive] Failed to remove container for session={}: {}", sessionId, e.message)
        }
    }

    /**
     * Attempts to persist the workspace of a container that is not in the memory cache.
     *
     * This handles the edge case where the service was restarted and [scanAndRestore]
     * hasn't run yet (or failed for this session), but the container still exists with
     * un-persisted workspace changes. We create a temporary [DockerSandbox] to extract
     * the workspace tar, persist it, and then close the sandbox.
     */
    private fun persistFromOrphanedContainer(sessionId: String) {
        val containerInfo = inspectExistingContainer(sessionId) ?: return
        val (containerId, isRunning) = containerInfo

        log.info("[keepAlive] Found orphaned container for session={}, running={}, attempting snapshot persist", sessionId, isRunning)
        try {
            // Start the container if stopped so we can exec into it
            if (!isRunning) {
                dockerExecutor.execute(listOf("docker", "start", "agentscope-sandbox-$sessionId"))
            }

            val state = DockerSandboxState()
            state.setSessionId(sessionId)
            state.setImage(image)
            state.setWorkspaceRoot(workspaceRoot)
            state.setContainerOwned(false)
            state.setWorkspaceRootReady(true)
            state.setWorkspaceSpec(WorkspaceSpec())
            state.setContainerId(containerId)
            state.setSnapshot(snapshotSpec!!.build(sessionId))

            val sandbox = sandboxFactory.create(state)
            sandbox.start()

            try {
                val snapshot = sandbox.state.snapshot
                if (snapshot != null && snapshot.isPersistenceEnabled) {
                    sandbox.persistWorkspace().use { archive ->
                        snapshot.persist(archive)
                    }
                    log.info("[keepAlive] Workspace snapshot persisted for orphaned session={}", sessionId)
                }
            } catch (e: Exception) {
                log.warn("[keepAlive] Failed to persist orphaned snapshot for session={}: {}", sessionId, e.message)
            }

            try {
                sandbox.close()
            } catch (e: Exception) {
                log.debug("[keepAlive] sandbox.close() failed for orphaned session={}", sessionId)
            }
        } catch (e: Exception) {
            log.warn("[keepAlive] Failed to attach to orphaned container for session={}: {}", sessionId, e.message)
        }
    }

    /**
     * Persists workspace snapshots for all currently managed sandboxes WITHOUT destroying the containers.
     *
     * This is intended for graceful shutdown: the agent-service is stopping, and we want to
     * ensure all workspace state is saved to snapshot storage before the process exits.
     * The containers themselves may continue running and will be re-attached on next startup
     * via [scanAndRestore].
     */
    fun persistAll() {
        if (sandboxes.isEmpty()) {
            log.info("[keepAlive] persistAll: no managed sandboxes to persist")
            return
        }
        log.info("[keepAlive] persistAll: persisting {} sandbox(es) before shutdown", sandboxes.size)
        var successCount = 0
        var failCount = 0
        for ((sessionId, entry) in sandboxes) {
            try {
                val snapshot = entry.sandbox.state.snapshot
                if (snapshot != null && snapshot.isPersistenceEnabled) {
                    entry.sandbox.persistWorkspace().use { archive ->
                        snapshot.persist(archive)
                    }
                    successCount++
                    log.info("[keepAlive] Snapshot persisted for session={} on shutdown", sessionId)
                } else {
                    log.debug("[keepAlive] Snapshot not enabled for session={}, skipping", sessionId)
                }
            } catch (e: Exception) {
                failCount++
                log.warn("[keepAlive] Failed to persist snapshot for session={} on shutdown: {}", sessionId, e.message)
            }
        }
        log.info("[keepAlive] persistAll complete: success={}, failed={}, total={}", successCount, failCount, sandboxes.size)
    }

    /**
     * Stops and removes all managed sandbox containers.
     */
    fun destroyAll() {
        sandboxes.keys.toList().forEach { destroy(it) }
    }

    /**
     * Removes sandboxes that have been idle for longer than maxIdleTimeMs.
     */
    private fun cleanupIdle() {
        val now = System.currentTimeMillis()
        val idleSessions = sandboxes.entries
            .filter { now - it.value.lastAccessTime > maxIdleTimeMs }
            .map { it.key }

        if (idleSessions.isNotEmpty()) {
            log.info("[keepAlive] Cleaning up {} idle sandbox(es)", idleSessions.size)
            idleSessions.forEach { destroy(it) }
        }
    }

    /**
     * Returns the [DockerSandbox] for the given session, or null if not managed.
     * Only checks in-memory cache — does NOT attempt to attach to existing Docker containers.
     * Does NOT create a new sandbox — use [getOrCreate] for that.
     */
    fun getSandbox(sessionId: String): DockerSandbox? {
        val cached = sandboxes[sessionId]
        if (cached != null) {
            cached.lastAccessTime = System.currentTimeMillis()
            return cached.sandbox
        }
        return null
    }

    /**
     * Attaches to an existing Docker container for the given session, if it exists.
     * This is useful when the service restarts but Docker containers are still running.
     * Returns the attached sandbox, or null if no container exists.
     */
    fun attachToExisting(sessionId: String): DockerSandbox? {
        // First check in-memory cache
        val cached = sandboxes[sessionId]
        if (cached != null) {
            cached.lastAccessTime = System.currentTimeMillis()
            return cached.sandbox
        }

        // Check if Docker container exists
        val containerName = "agentscope-sandbox-$sessionId"
        return try {
            val result = dockerExecutor.execute(
                listOf("docker", "inspect", "--format", "{{.Id}}|{{.State.Running}}", containerName),
            )
            val output = result.output

            if (output.contains("|")) {
                val parts = output.split("|")
                val containerId = parts[0]
                val isRunning = parts[1] == "true"
                log.info("[keepAlive] Found existing container for session={}, containerId={}, running={}", sessionId, containerId, isRunning)

                // If container is stopped, start it first
                if (!isRunning) {
                    log.info("[keepAlive] Starting stopped container for session={}", sessionId)
                    dockerExecutor.execute(listOf("docker", "start", containerName))
                }

                // Attach to existing container WITHOUT removing it
                val state = DockerSandboxState()
                state.setSessionId(sessionId)
                state.setImage(image)
                state.setWorkspaceRoot(workspaceRoot)
                state.setContainerOwned(false)
                state.setWorkspaceRootReady(true)
                state.setWorkspaceSpec(WorkspaceSpec())
                state.setContainerId(containerId)
                if (snapshotSpec != null) {
                    state.setSnapshot(snapshotSpec.build(sessionId))
                }

                val sandbox = sandboxFactory.create(state)
                sandbox.start()

                // Use putIfAbsent to avoid race condition with concurrent getOrCreate()/scanAndRestore()
                val entry = SandboxEntry(sandbox)
                val previous = sandboxes.putIfAbsent(sessionId, entry)
                if (previous != null) {
                    log.debug("[keepAlive] Session {} already in memory during attach, discarding duplicate", sessionId)
                    try {
                        sandbox.close()
                    } catch (e: Exception) {
                        log.debug("[keepAlive] Failed to close duplicate sandbox for session={}", sessionId)
                    }
                    return previous.sandbox
                }
                log.info("[keepAlive] Attached to existing container for session={}, preserving data", sessionId)
                sandbox
            } else {
                log.debug("[keepAlive] No container found for session={}", sessionId)
                null
            }
        } catch (e: Exception) {
            log.warn("[keepAlive] Failed to attach to container for session={}: {}", sessionId, e.message, e)
            null
        }
    }

    /**
     * Returns the set of session IDs that currently have active sandboxes.
     */
    fun getActiveSessions(): Set<String> = sandboxes.keys.toSet()

    /**
     * Evicts the oldest (least recently accessed) sandbox to make room for a new one.
     */
    private fun evictOldest() {
        val oldest = sandboxes.entries
            .minByOrNull { it.value.lastAccessTime }
            ?.key

        if (oldest != null) {
            log.info("[keepAlive] Evicting oldest sandbox for session={}", oldest)
            destroy(oldest)
        }
    }
}
