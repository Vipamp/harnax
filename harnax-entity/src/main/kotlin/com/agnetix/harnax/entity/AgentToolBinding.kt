package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Agent-Tool binding entity.
 * Represents a tool association with an agent, including per-binding settings
 * and environment variable binding snapshots.
 */
@Schema(description = "Agent Tool Binding entity")
class AgentToolBinding : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Binding ID")
    var id: Long = 0

    @Schema(description = "FK to agent.id")
    var agentId: Long = 0

    @Schema(description = "FK to agent_tool.id")
    var toolId: Long = 0

    @Schema(description = "Requires human confirmation (0: No, 1: Yes)")
    var needConfirm: Int = 0

    @Schema(description = "Environment bindings JSON snapshot")
    var envBindings: String? = null

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
