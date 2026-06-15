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
        val state = DockerSandboxState().apply {
            setSessionId(sid)
            setImage(image)
            setWorkspaceRoot(workspaceRoot)
            setContainerOwned(true)
            setWorkspaceRootReady(false)
            if (workspaceSpec != null) {
                setWorkspaceSpec(workspaceSpec)
            }
            if (snapshotSpec != null) {
                setSnapshot(snapshotSpec.build(sid))
            }
        }
        val sandbox = DockerSandbox(state)
        try {
            sandbox.start()
            log.info("[keepAlive] Sandbox started for session={}, containerId={}", sid, state.containerId)
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
