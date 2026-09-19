package com.agnetix.harnax.harness.config

import io.agentscope.harness.agent.IsolationScope

/**
 * Docker sandbox configuration.
 *
 * @param enabled whether sandbox is enabled; when false, falls back to local/remote filesystem
 * @param image Docker image to use for sandbox containers
 * @param workspaceRoot workspace root path inside the container
 * @param isolationScope sandbox isolation scope, SESSION ensures each session gets its own container
 * @param keepAlive when true, the sandbox container remains running between agent calls,
 *   avoiding the overhead of container destruction and recreation; when false (default),
 *   the container is stopped and removed after each call
 * @param network Docker network mode or name passed to `docker run --network`;
 *   when null, Docker uses the default bridge network; set to "host" to share host network,
 *   or a custom network name (e.g. "docker-new_harnax-network") for inter-container communication
 * @param cliPluginsEnabled when true, CLI plugins are initialized inside the sandbox container
 * @param pluginImage Docker image containing CLI plugins (used when cliPluginsEnabled=true)
 * @param pluginAdminUrl admin service URL accessible from within the container (for CLI auth)
 * @param pluginInternalSecret service-level internal secret (for CLI auth)
 * @param keepAliveMaxIdleTimeMs how long a keep-alive sandbox survives without being used, after which
 *   the idle reaper removes its container. The clock refreshes when a turn attaches the sandbox, not
 *   while it runs, so this must stay above the longest single turn (the member-turn timeout and the
 *   chat link timeout both sit well under the default).
 */
data class SandboxConfig(
    val enabled: Boolean = false,
    val image: String = "python:3.11-slim",
    val workspaceRoot: String = "/workspace",
    val isolationScope: IsolationScope = IsolationScope.SESSION,
    val keepAlive: Boolean = false,
    val network: String? = null,
    val cliPluginsEnabled: Boolean = false,
    val pluginImage: String = "harnax-sandbox:latest",
    val pluginAdminUrl: String = "",
    val pluginInternalSecret: String = "",
    val keepAliveMaxIdleTimeMs: Long = 30 * 60 * 1000L,
)
