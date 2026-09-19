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
 * @param team budgets of one team run, enforced by the runtime rather than negotiated by the lead
 */
data class HarnessConfig(
    val sandbox: SandboxConfig = SandboxConfig(),
    val enableWorkspaceContext: Boolean = false,
    val enableMemoryHooks: Boolean = false,
    val enableSessionPersistence: Boolean = true,
    val mcpStdioEnabled: Boolean = false,
    val team: TeamConfig = TeamConfig(),
)

/**
 * Limits for a single team run (design section 9.3).
 *
 * @param maxDelegations how many member tasks one lead may start before further delegation is refused
 * @param memberTurnTimeoutSeconds how long a single member turn may run before the delegation is
 *   reported to the lead as failed. A long member is normal, so this is generous; it exists so a hung
 *   model call cannot hold the root stream open forever.
 * @param confirmTimeoutSeconds how long a member may wait for a human confirmation. A timeout is never
 *   an approval — the task comes back to the lead as failed (design section 9.2).
 * @param confirmHeartbeatSeconds how often the root stream emits a keep-alive while a confirmation waits.
 *   The wait is otherwise silent and every hop of the stream kills on silence (session-router 120s,
 *   channel-service 180s), which would drop a run one answer away from continuing.
 * @param maxArtifactBytes largest file a member may publish or fetch, counted on the original bytes
 */
data class TeamConfig(
    val maxDelegations: Int = 20,
    val memberTurnTimeoutSeconds: Long = 900,
    val confirmTimeoutSeconds: Long = 600,
    val confirmHeartbeatSeconds: Long = 30,
    val maxArtifactBytes: Long = 20L * 1024 * 1024,
)
