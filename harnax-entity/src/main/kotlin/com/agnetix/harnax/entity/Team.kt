package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Multi-agent team entity.
 *
 * A team owns its lead: [systemPrompt] and [modelId] are the lead's configuration and exist nowhere
 * else, while its members stay ordinary [Agent] rows with their own tool, MCP, skill and CLI bindings.
 * So an agent has exactly two possible roles — conversable on its own, or a member of some team — and
 * no row impersonates a lead.
 */
@Schema(description = "Multi-agent team entity")
class Team : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Team name")
    var name: String = ""

    @Schema(description = "Team description")
    var description: String = ""

    @Schema(description = "The lead's whole system prompt, orchestration rules included")
    var systemPrompt: String = ""

    @Schema(description = "FK to model.id, the model the lead runs on")
    var modelId: Long = 0

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Public status (0:no, 1:yes)")
    var isPublic: Int = 0

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
