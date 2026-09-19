package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.Team
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Team response object
 */
@Schema(description = "Team response object")
data class TeamResponse(
    @Schema(description = "ID", example = "1")
    val id: Long? = null,
    @Schema(description = "Team name", example = "Research report team")
    val name: String? = null,
    @Schema(description = "Team description")
    val description: String? = null,
    @Schema(description = "Lead agent ID", example = "1")
    val leadAgentId: Long? = null,
    @Schema(description = "Lead agent name", example = "coordinator")
    val leadAgentName: String? = null,
    @Schema(description = "Lead agent description")
    val leadAgentDescription: String? = null,
    @Schema(description = "Team instructions appended to the lead prompt")
    val instructions: String? = null,
    @Schema(description = "Members in assembly order")
    val memberList: List<MemberItem> = emptyList(),
    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int? = null,
    @Schema(description = "Whether public (0:no, 1:yes)", example = "0")
    val isPublic: Int? = null,
    @Schema(description = "Tenant ID", example = "1")
    val tenantId: Long? = null,
    @Schema(description = "Creator", example = "admin")
    val creator: String? = null,
    @Schema(description = "Creation time")
    val createTime: LocalDateTime? = null,
    @Schema(description = "Update time")
    val updateTime: LocalDateTime? = null,
) {
    /**
     * One member as displayed by the team page. [agentAvailable] is false when the referenced agent
     * was deleted or disabled since the team was saved, so the page can name the broken member instead
     * of showing an empty row the user cannot explain.
     */
    @Schema(description = "Team member item")
    data class MemberItem(
        @Schema(description = "Member agent ID", example = "2")
        val agentId: Long,
        @Schema(description = "Member agent name", example = "researcher")
        val agentName: String,
        @Schema(description = "Member agent description")
        val agentDescription: String? = null,
        @Schema(description = "Delegation description within this team")
        val delegationDescription: String? = null,
        @Schema(description = "Member agent status (0:disabled, 1:enabled)")
        val agentStatus: Int? = null,
        @Schema(description = "Whether the referenced agent still resolves")
        val agentAvailable: Boolean = true,
    )

    companion object {
        @JvmStatic
        fun fromEntity(team: Team): TeamResponse = TeamResponse(
            id = team.id,
            name = team.name,
            description = team.description,
            leadAgentId = team.leadAgentId,
            instructions = team.instructions,
            status = team.status,
            isPublic = team.isPublic,
            tenantId = team.tenantId,
            creator = team.creator,
            createTime = team.createTime,
            updateTime = team.updateTime,
        )
    }
}
