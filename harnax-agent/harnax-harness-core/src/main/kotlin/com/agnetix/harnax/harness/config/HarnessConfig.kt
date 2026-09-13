package com.agnetix.harnax.harness.config

/**
 * Global Harness configuration controlling the agent runtime behaviour.
 *
 * @param sandbox Docker sandbox configuration; when disabled, uses local or remote filesystem
 * @param enableWorkspaceContext whether to inject AGENTS.md / workspace context into system prompt
 * @param enableMemoryHooks whether to enable built-in memory flush/maintenance hooks
 * @param enableSessionPersistence whether to enable automatic session persistence via SessionPersistenceHook
 * @param mcpStdioEnabled whether an MCP server of type stdio may be started as a local process; off by
 *   default because this process runs without isolation against the host (see the admin's
 *   `McpStdioPolicy`, which holds such rows back from delivery as the first line of defence)
 */
data class HarnessConfig(
    val sandbox: SandboxConfig = SandboxConfig(),
    val enableWorkspaceContext: Boolean = false,
    val enableMemoryHooks: Boolean = false,
    val enableSessionPersistence: Boolean = true,
    val mcpStdioEnabled: Boolean = false,
)
