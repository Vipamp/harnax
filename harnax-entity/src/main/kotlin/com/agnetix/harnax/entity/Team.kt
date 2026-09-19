package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Multi-agent team entity.
 *
 * A team is a grouping configuration only: it references an existing agent as its lead and never
 * carries a second set of model, tool, MCP, skill or CLI bindings. Those stay on [Agent], so the same
 * agent can lead one team, be a member of another, and still be used standalone.
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

    @Schema(description = "FK to agent.id, the agent that orchestrates this team")
    var leadAgentId: Long = 0

    @Schema(description = "Team instructions appended to the lead role prompt")
    var instructions: String = ""

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
