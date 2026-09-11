package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Audit row for one MCP authorization event or tool call. Outcome and latency only: request bodies,
 * response bodies and Authorization headers never enter this table, because admins who are not the
 * token owner can read it.
 */
@Schema(description = "MCP authorization / call audit entry")
class McpCallLog : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        const val ACTION_ISSUE = "ISSUE"
        const val ACTION_REFRESH = "REFRESH"
        const val ACTION_REVOKE = "REVOKE"
        const val ACTION_CALL = "CALL"

        const val OUTCOME_OK = "OK"
        const val OUTCOME_AUTH_FAILED = "AUTH_FAILED"
        const val OUTCOME_NEEDS_CONSENT = "NEEDS_CONSENT"
        const val OUTCOME_ERROR = "ERROR"
    }

    @Schema(description = "Log ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "FK to sys_user.id; null when the session owner could not be resolved")
    var userId: Long? = null

    @Schema(description = "FK to mcp_server.id")
    var mcpId: Long = 0

    @Schema(description = "Runtime session the call came from")
    var sessionId: String? = null

    @Schema(description = "Tool name for call-level audit; null for token issuance")
    var toolName: String? = null

    @Schema(description = "ISSUE / REFRESH / REVOKE / CALL")
    var action: String = ACTION_ISSUE

    @Schema(description = "OK / AUTH_FAILED / NEEDS_CONSENT / ERROR")
    var outcome: String = OUTCOME_OK

    @Schema(description = "Duration in milliseconds")
    var latencyMs: Long = 0

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()
}
