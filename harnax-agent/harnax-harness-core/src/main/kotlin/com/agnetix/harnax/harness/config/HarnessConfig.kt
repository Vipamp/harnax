package com.agnetix.harnax.harness.config

/**
 * Global Harness configuration controlling the agent runtime behaviour.
 *
 * @param sandbox Docker sandbox configuration; when disabled, uses local or remote filesystem
 * @param enableWorkspaceContext whether to inject AGENTS.md / workspace context into system prompt
 * @param enableMemoryHooks whether to enable built-in memory flush/maintenance hooks
 * @param enableSessionPersistence whether to enable automatic session persistence via SessionPersistenceHook
 * @param turnTimeoutSeconds timeout applied by [com.agnetix.harnax.harness.HarnessAgentWrapper]
 *   to both the batch and the streaming call: it caps a whole batch turn, and on a stream it bounds the
 *   silence between events. It replaces the former per-tool `@ToolMeta(timeoutSeconds)`:
 *   a tool-level value reached the entity but was read by nobody, so the only timeout that ever applied
 *   was this one. It applies to a lone agent — a turn inside a team gets
 *   [TeamConfig.turnTimeoutSeconds] instead.
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
    val turnTimeoutSeconds: Long = 300,
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
 * @param turnTimeoutSeconds budget of one team turn, applied to the wrapper the way
 *   [HarnessConfig.turnTimeoutSeconds] is: it caps a whole batch turn and bounds the silence between
 *   events on a stream. A team turn is silent where a lone agent's is not — a member can run one tool
 *   for [memberTurnTimeoutSeconds] without the root stream seeing a single event — so this has to stay
 *   above every other budget in here. Below that, this limit fires instead of the team layer's, and a
 *   run that should have come back as a failed delegation comes back as a broken conversation.
 */
data class TeamConfig(
    val maxDelegations: Int = 20,
    val memberTurnTimeoutSeconds: Long = 900,
    val confirmTimeoutSeconds: Long = 600,
    val confirmHeartbeatSeconds: Long = 30,
    val maxArtifactBytes: Long = 20L * 1024 * 1024,
    val turnTimeoutSeconds: Long = 1_800,
)
