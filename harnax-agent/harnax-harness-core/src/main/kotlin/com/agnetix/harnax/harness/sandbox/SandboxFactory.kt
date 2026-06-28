package com.agnetix.harnax.harness.sandbox

import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState

/**
 * Factory for creating [DockerSandbox] instances.
 * Extracted from [KeepAliveSandboxManager] to enable unit testing with mock sandboxes.
 */
fun interface SandboxFactory {
    /**
     * Creates a [DockerSandbox] from the given state.
     */
    fun create(state: DockerSandboxState): DockerSandbox
}
