package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Responses for the call-metrics pages.
 *
 * admin serialises with `default-property-inclusion: non_null`, so a null deletes the key the page reads.
 * Every field that always has a value is non-null with a default; the four that genuinely may be absent
 * (`subjectId`, `dimensionId`, and the payload columns on the detail row) stay nullable and the page reads
 * them with `?.`.
 *
 * The three instant columns are `yyyy-MM-dd HH:mm:ss` strings the service formats, not `LocalDateTime`.
 * Jackson serialises `java.time` in its ISO form and does not apply `spring.jackson.date-format` to it, so
 * a typed field would arrive as `2026-10-05T10:00:00` while every other time label in this page family —
 * `ToolMetricsPoint.timePoint` and the token-stats trend before it — is a formatted string.
 */
data class ToolMetricsRow(
    @Schema(description = "Origin bucket: builtin / mcp / cli / shell / framework; empty when grouped by agent or session")
    val kind: String = "",
    @Schema(
        description = "The row's key: the tool name when grouped by tool, the session id when grouped by " +
            "session, the id as text when grouped by mcp or cli, the agent id as text otherwise",
    )
    val subjectKey: String = "",
    @Schema(
        description = "MCP server id or CLI package id when kind is mcp or cli, the agent id when grouped by " +
            "agent, absent otherwise. This is the value the drill-down predicate is built from.",
    )
    val subjectId: Long? = null,
    @Schema(description = "Tool name; empty when grouped by mcp, cli, agent or session")
    val toolName: String = "",
    @Schema(
        description = "This row's own subject as its registered name: the MCP server, CLI package or agent " +
            "name, the session title, or the tool name when grouped by tool. Falls back to subjectKey when " +
            "nothing is registered behind the id.",
    )
    val subjectName: String = "",
    @Schema(
        description = "Owning MCP server or CLI package name, present only on a row whose kind is mcp or cli; " +
            "a tool row keeps its name and adds the server that answered, so the row says which one ran it",
    )
    val parentName: String = "",
    val calls: Long = 0L,
    val successes: Long = 0L,
    val errors: Long = 0L,
    val denials: Long = 0L,
    val interruptions: Long = 0L,
    @Schema(description = "Successes over calls as a fraction; the page formats the percentage")
    val successRate: Double = 0.0,
    val avgDurationMs: Long = 0L,
    @Schema(description = "'<=' when the 95th percentile lands inside a bucket, '>' for the open-ended one; on the agent and session dimensions the row's longest call instead")
    val p95Operator: String = "<=",
    @Schema(description = "Upper bound of the bucket p95Operator points at, in ms; `max(duration_ms)` on the agent and session dimensions")
    val p95Ms: Long = 0L,
    @Schema(description = "`yyyy-MM-dd HH:mm:ss`; hour precision on the aggregate path, second precision on the detail path")
    val lastSeenAt: String? = null,
)

data class ToolMetricsSummaryResponse(
    @Schema(description = "First hour answered, `yyyy-MM-dd HH:mm:ss`, after the clamps")
    val from: String = "",
    @Schema(description = "Last hour answered, inclusive, `yyyy-MM-dd HH:mm:ss`, after the clamps")
    val to: String = "",
    @Schema(description = "The dimension answered, after an unrecognised value falls back to tool")
    val groupBy: String = "tool",
    val totalCalls: Long = 0L,
    val totalSuccesses: Long = 0L,
    val successRate: Double = 0.0,
    @Schema(description = "Errors, denials and interruptions together")
    val failingCalls: Long = 0L,
    val p95Operator: String = "<=",
    val p95Ms: Long = 0L,
    val rows: List<ToolMetricsRow> = emptyList(),
)

data class ToolMetricsPoint(
    val timePoint: String = "",
    val kind: String = "",
    @Schema(description = "MCP server id / CLI package id; absent for builtin, shell and framework")
    val dimensionId: Long? = null,
    @Schema(description = "Tool name as the model sees it; the matched command name when kind = cli")
    val dimensionName: String = "",
    val calls: Long = 0L,
    val successes: Long = 0L,
    val errors: Long = 0L,
    val denials: Long = 0L,
    val interruptions: Long = 0L,
    val avgDurationMs: Long = 0L,
)

data class ToolMetricsTimeSeriesResponse(
    @Schema(
        description = "First hour answered, `yyyy-MM-dd HH:mm:ss`; week and month buckets align backwards, so " +
            "the first point can open before this hour",
    )
    val from: String = "",
    @Schema(description = "Last hour answered, inclusive, `yyyy-MM-dd HH:mm:ss`")
    val to: String = "",
    @Schema(description = "Bucket the trend was folded to: hour / day / week / month")
    val granularity: String = "day",
    val points: List<ToolMetricsPoint> = emptyList(),
)

data class ToolInvocationRow(
    val id: Long = 0L,
    val kind: String = "",
    val toolName: String = "",
    val agentId: Long? = null,
    val sessionId: String = "",
    @Schema(description = "Registered title of that session; absent when the id has no session row")
    val sessionName: String? = null,
    val userId: Long? = null,
    val mcpId: Long? = null,
    val cliId: Long? = null,
    val outcome: String = "",
    val errorMessage: String? = null,
    @Schema(description = "Absent when payload capture is off")
    val argsJson: String? = null,
    @Schema(description = "Leading part of the tool result, truncated; absent when capture is off")
    val resultExcerpt: String? = null,
    val durationMs: Long = 0L,
    @Schema(description = "`yyyy-MM-dd HH:mm:ss`; both instant columns are formatted by the service")
    val startTime: String? = null,
    val ts: String? = null,
)
