package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * One tool invocation, stored once at its terminal state.
 *
 * Append-only. The window this table covers is bounded by design: long trends live in
 * [ToolInvocationStats], so a reader that needs a single call's arguments or error text asks within the
 * retention window and a reader that needs a trend never touches these rows.
 *
 * [kind] and [outcome] are strings rather than an enum because they are the vocabulary of the analytics
 * page and of the aggregate table's rows; renaming a constant here is a data migration, not a code change.
 */
@Schema(description = "Tool invocation event")
class ToolInvocationLog : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** A tool Admin delivered with the agent's spec. */
        const val KIND_BUILTIN = "builtin"

        /** A tool that came from an MCP server registered for this session. */
        const val KIND_MCP = "mcp"

        /** A CLI package Admin delivered, run through the shell tool. */
        const val KIND_CLI = "cli"

        /** The shell tool used for something that is not a delivered CLI. */
        const val KIND_SHELL = "shell"

        /** A tool the harness itself registered; nothing in Admin's tables names it. */
        const val KIND_FRAMEWORK = "framework"

        const val OUTCOME_SUCCESS = "SUCCESS"
        const val OUTCOME_ERROR = "ERROR"
        const val OUTCOME_DENIED = "DENIED"
        const val OUTCOME_INTERRUPTED = "INTERRUPTED"
    }

    @Schema(description = "Invocation ID")
    var id: Long = 0

    @Schema(description = "Owning tenant, null when the delivered spec named none")
    var tenantId: Long? = null

    @Schema(description = "Owning agent, null for a team lead")
    var agentId: Long? = null

    @Schema(description = "Session that produced the call")
    var sessionId: String? = null

    @Schema(description = "End user behind the call, null for channel sessions and service keys")
    var userId: Long? = null

    @Schema(description = "Origin (builtin / mcp / cli / shell / framework)")
    var kind: String = KIND_FRAMEWORK

    @Schema(description = "Tool name as the model sees it; the command name when kind is cli")
    var toolName: String = ""

    @Schema(description = "MCP server row, set only when kind is mcp")
    var mcpId: Long? = null

    @Schema(description = "CLI package row, set only when kind is cli")
    var cliId: Long? = null

    @Schema(description = "Terminal state (SUCCESS / ERROR / DENIED / INTERRUPTED)")
    var outcome: String = OUTCOME_SUCCESS

    @Schema(description = "Failure reason, truncated")
    var errorMessage: String? = null

    @Schema(description = "Tool input as JSON, truncated; null when payload capture is off")
    var argsJson: String? = null

    @Schema(description = "Leading part of the tool result, truncated")
    var resultExcerpt: String? = null

    @Schema(description = "End time minus start time, milliseconds")
    var durationMs: Long = 0

    @Schema(description = "Call start")
    var startTime: LocalDateTime? = null

    @Schema(description = "Call end")
    var endTime: LocalDateTime? = null

    @Schema(description = "Recorded time, equal to endTime")
    var ts: LocalDateTime = LocalDateTime.now()
}
