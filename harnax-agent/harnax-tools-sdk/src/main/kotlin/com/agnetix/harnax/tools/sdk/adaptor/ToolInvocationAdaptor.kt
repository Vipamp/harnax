package com.agnetix.harnax.tools.sdk.adaptor

/**
 * Files the fact that one tool call of one session reached a terminal state.
 *
 * The event source is the acting middleware, which sees every tool the model could call: a delivered
 * tool, an MCP server's tool, the shell, and a harness built-in. That is why this is a runtime contract
 * rather than a method on a tool base class — a recording point that lives on an implementation class
 * only ever sees the implementations that go through it, which is exactly how the previous log came to
 * hold nothing but built-in tools.
 *
 * Implementations must return without waiting for the database and must never throw. This runs while the
 * model's acting step is streaming: a reporter that blocks adds its latency to the answer, and one that
 * throws fails the answer over a lost counter. Failures belong to the implementation — log them, drop
 * the event, never rethrow.
 */
fun interface ToolInvocationAdaptor {
    fun emit(event: ToolInvocationEvent)
}

/**
 * One invocation, already classified, with its own attribution carried in.
 *
 * [kind], [mcpId] and [cliId] are flat rather than a value object because the SDK holds no dependency on
 * the harness module that decides them; [ToolInvocationAdaptor] implementations read them as they arrive.
 * Times are epoch milliseconds because the caller measured them with `System.currentTimeMillis()` and a
 * conversion to wall-clock columns belongs to whoever writes the row.
 */
data class ToolInvocationEvent(
    /** Tenant of the run, from `AgentSpec.tenantId`. Null means nothing attributed it: the row stays unattributed. */
    val tenantId: Long?,
    /** Agent of the run, null for a team lead, which has no `agent` row. */
    val agentId: Long?,
    val sessionId: String,
    /** End user behind the run, null for a channel conversation or a service key. */
    val userId: Long?,
    /** `builtin` / `mcp` / `cli` / `shell` / `framework`. */
    val kind: String,
    /** Tool name as the model sees it, or the CLI command name when [kind] is `cli`. */
    val toolName: String,
    val mcpId: Long? = null,
    val cliId: Long? = null,
    /** `SUCCESS` / `ERROR` / `DENIED` / `INTERRUPTED`. A non-terminal state never produces an event. */
    val outcome: String,
    /** Tool input as JSON, or null when payload capture is off. */
    val argsJson: String?,
    /** Accumulated result text, or null when payload capture is off. */
    val resultText: String?,
    /** Failure reason, present on the non-success outcomes. */
    val errorMessage: String?,
    val startEpochMilli: Long,
    val endEpochMilli: Long,
)
