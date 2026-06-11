package com.agnetix.harnax.harness.config

/**
 * Global Harness configuration controlling the agent runtime behaviour.
 *
 * @param sandbox Docker sandbox configuration; when disabled, uses local or remote filesystem
 * @param enableWorkspaceContext whether to inject AGENTS.md / workspace context into system prompt
 * @param enableMemoryHooks whether to enable built-in memory flush/maintenance hooks
 * @param enableSessionPersistence whether to enable automatic session persistence via SessionPersistenceHook
 */
data class HarnessConfig(
    val sandbox: SandboxConfig = SandboxConfig(),
    val enableWorkspaceContext: Boolean = false,
    val enableMemoryHooks: Boolean = false,
    val enableSessionPersistence: Boolean = true,
)
