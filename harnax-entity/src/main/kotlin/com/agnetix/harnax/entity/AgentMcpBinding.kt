package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Agent-MCP binding entity.
 * Represents an MCP server association with an agent, including per-binding settings
 * and environment variable binding snapshots.
 */
@Schema(description = "Agent MCP Binding entity")
class AgentMcpBinding : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Binding ID")
    var id: Long = 0

    @Schema(description = "FK to agent.id")
    var agentId: Long = 0

    @Schema(description = "FK to mcp_server.id")
    var mcpId: Long = 0

    @Schema(description = "Whether to skip if MCP is unavailable (true/false)")
    var enableSkip: String = "false"

    @Schema(description = "Environment bindings JSON snapshot")
    var envBindings: String? = null

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
