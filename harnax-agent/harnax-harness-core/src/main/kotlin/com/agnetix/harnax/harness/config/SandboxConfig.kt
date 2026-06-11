package com.agnetix.harnax.harness.config

import io.agentscope.harness.agent.IsolationScope

/**
 * Docker sandbox configuration.
 *
 * @param enabled whether sandbox is enabled; when false, falls back to local/remote filesystem
 * @param image Docker image to use for sandbox containers
 * @param workspaceRoot workspace root path inside the container
 * @param isolationScope sandbox isolation scope, SESSION ensures each session gets its own container
 */
data class SandboxConfig(
    val enabled: Boolean = false,
    val image: String = "python:3.11-slim",
    val workspaceRoot: String = "/workspace",
    val isolationScope: IsolationScope = IsolationScope.SESSION,
)
