package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.Team
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Team response object.
 *
 * [systemPrompt] and [modelId] are the lead's own configuration, read back from the team row; there is no
 * lead agent to name any more.
 */
@Schema(description = "Team response object")
data class TeamResponse(
    @Schema(description = "ID", example = "1")
    val id: Long? = null,
    @Schema(description = "Team name, which is also the lead's name", example = "Research report team")
    val name: String? = null,
    @Schema(description = "Team description")
    val description: String? = null,
    @Schema(description = "System prompt of the lead")
    val systemPrompt: String = "",
    @Schema(description = "Chat model ID of the lead", example = "1")
    val modelId: Long = 0,
    @Schema(description = "Chat model name of the lead", example = "qwen3-max")
    val modelName: String? = null,
    @Schema(description = "Skills the lead is given, in binding order")
    val skillList: List<SkillItem> = emptyList(),
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

    /**
     * One lead skill. [skillAvailable] is false for a skill row that has since been deleted, for the
     * same reason [MemberItem.agentAvailable] exists: the delivery side drops it with a log line, and a
     * panel that showed nothing would leave the operator wondering where the skill went.
     */
    @Schema(description = "Team lead skill item")
    data class SkillItem(
        @Schema(description = "Skill ID", example = "3")
        val skillId: Long,
        @Schema(description = "Skill name", example = "report-writing")
        val skillName: String,
        @Schema(description = "Skill description")
        val skillDescription: String? = null,
        @Schema(description = "Owning repository ID", example = "1")
        val repositoryId: Long? = null,
        @Schema(description = "Owning repository name", example = "qoder-skills")
        val repositoryName: String? = null,
        @Schema(description = "Whether the referenced skill still resolves")
        val skillAvailable: Boolean = true,
    )

    companion object {
        @JvmStatic
        fun fromEntity(team: Team): TeamResponse = TeamResponse(
            id = team.id,
            name = team.name,
            description = team.description,
            systemPrompt = team.systemPrompt,
            modelId = team.modelId,
            status = team.status,
            isPublic = team.isPublic,
            tenantId = team.tenantId,
            creator = team.creator,
            createTime = team.createTime,
            updateTime = team.updateTime,
        )
    }
}
