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
 */
class KeepAliveSandboxManager(
    private val image: String,
    private val workspaceRoot: String,
) {

    init {
        require(image.isNotBlank()) {
            "Sandbox image must not be blank. Check harness.sandbox.image configuration."
        }
        require(workspaceRoot.isNotBlank()) {
            "Sandbox workspaceRoot must not be blank. Check harness.sandbox.workspace-root configuration."
        }
    }

    private val log = LoggerFactory.getLogger(KeepAliveSandboxManager::class.java)
    private val sandboxes = ConcurrentHashMap<String, DockerSandbox>()

    /**
     * Returns a running [DockerSandbox] for the given session, creating one if necessary.
     * The container stays alive across calls — it is only destroyed via [destroy].
     *
     * @param sessionId session identifier, used as both the sandbox session ID and cache key
     * @param workspaceSpec workspace specification (projection, entries) for initial workspace init
     * @param snapshotSpec optional snapshot spec for workspace persistence on crash recovery
     */
    fun getOrCreate(
        sessionId: String,
        workspaceSpec: WorkspaceSpec?,
        snapshotSpec: SandboxSnapshotSpec?,
    ): DockerSandbox = sandboxes.computeIfAbsent(sessionId) { sid ->
        log.info("[keepAlive] Creating sandbox for session={}, image={}, workspaceRoot={}", sid, image, workspaceRoot)
        val state = DockerSandboxState()
        state.setSessionId(sid)
        state.setImage(image)
        state.setWorkspaceRoot(workspaceRoot)
        state.setContainerOwned(true)
        state.setWorkspaceRootReady(false)
        if (workspaceSpec != null) {
            state.setWorkspaceSpec(workspaceSpec)
        }
        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sid))
        }

        // Diagnostic + reflection fallback for image field
        val imageViaGetter = state.getImage()
        if (imageViaGetter == null) {
            log.warn("[keepAlive] getImage() returned null after setImage('{}'), using reflection fallback", image)
            try {
                val field = DockerSandboxState::class.java.getDeclaredField("image")
                field.isAccessible = true
                field.set(state, image)
                log.info("[keepAlive] Reflection set image={}, getImage()={}", image, state.getImage())
            } catch (e: Exception) {
                log.error("[keepAlive] Reflection fallback failed", e)
                throw RuntimeException("Cannot set DockerSandboxState.image", e)
            }
        }

        val sandbox = DockerSandbox(state)
        try {
            sandbox.start()
            log.info("[keepAlive] Sandbox started for session={}, containerId={}", sid, state.getContainerId())
        } catch (e: Exception) {
            log.error("[keepAlive] Failed to start sandbox for session={}", sid, e)
            throw RuntimeException("Failed to start keepAlive sandbox for session=$sid", e)
        }
        sandbox
    }

    /**
     * Stops and removes the sandbox container for the given session.
     */
    fun destroy(sessionId: String) {
        val sandbox = sandboxes.remove(sessionId)
        if (sandbox != null) {
            try {
                sandbox.close()
                log.info("[keepAlive] Sandbox destroyed for session={}", sessionId)
            } catch (e: Exception) {
                log.warn("[keepAlive] Failed to destroy sandbox for session={}: {}", sessionId, e.message)
            }
        }
    }

    /**
     * Stops and removes all managed sandbox containers.
     */
    fun destroyAll() {
        sandboxes.keys.toList().forEach { destroy(it) }
    }
}
