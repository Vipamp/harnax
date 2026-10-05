package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.entity.SkillVisibilityPolicy
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Skill response object
 */
@Schema(description = "Skill response object")
data class SkillResponse(
    @Schema(description = "ID", example = "1")
    val id: Long? = null,
    @Schema(description = "Skill name", example = "test-skill")
    val name: String? = null,
    @Schema(description = "Repository ID", example = "1")
    val repositoryId: Long? = null,
    @Schema(description = "Repository name", example = "qoder-skills")
    var repositoryName: String? = null,
    @Schema(description = "Repository URL", example = "https://github.com/example/repo")
    var repositoryUrl: String? = null,
    @Schema(description = "Branch name", example = "main")
    var repositoryBranch: String? = null,
    @Schema(description = "Skill description")
    val description: String? = null,
    @Schema(description = "skill.md content")
    val skillmd: String? = null,
    @Schema(description = "Resource information")
    val resources: String? = null,
    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int? = null,
    @Schema(description = "How many agents bind this skill. A bound skill can be neither disabled nor deleted from here")
    val boundAgentCount: Int = 0,
    @Schema(description = "How many teams bind this skill as their lead's configuration. Counts against the same guard")
    val boundTeamCount: Int = 0,
    @Schema(description = "Whether public (0:no, 1:yes)", example = "1")
    val isPublic: Int? = null,
    @Schema(description = "Creator", example = "admin")
    val creator: String? = null,
    @Schema(description = "Provenance: human / agent_promoted", example = "human")
    val origin: String? = null,
    @Schema(description = "Session an agent proposed this skill in; null for human skills")
    val originRef: String? = null,
    @Schema(description = "Creation time", example = "2026-03-16 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "Update time", example = "2026-03-16 12:00:00")
    val updateTime: LocalDateTime? = null,
    @Schema(description = "Runtime rollout rule of this skill; absent means no rule is stored, i.e. everybody may load it")
    val visibility: VisibilitySummary? = null,
) {
    /**
     * What one skill's visibility policy says, in the shape a list row can render.
     *
     * Deliberately narrower than the control plane's own response: a page of twenty skills must not carry
     * twenty allow-lists, and the usernames behind those ids are read by whoever opens the form. The counts
     * and the percentage are enough for the row to be honest about what it is.
     */
    @Schema(description = "Summary of one skill's runtime visibility policy")
    data class VisibilitySummary(
        @Schema(description = "ALL / CANARY / ALLOW_LIST / ENV", example = "CANARY")
        val mode: String = SkillVisibilityPolicy.MODE_ALL,
        @Schema(description = "Rollout percentage, when mode is CANARY", example = "20")
        val canaryPct: Int? = null,
        @Schema(description = "How many users are allowed, when mode is ALLOW_LIST", example = "3")
        val userCount: Int = 0,
        @Schema(description = "Environment labels, when mode is ENV")
        val environments: List<String> = emptyList(),
    )

    companion object {
        /**
         * Convert Skill entity to SkillResponse
         */
        @JvmStatic
        fun fromEntity(
            skill: Skill?,
            repository: SkillRepository? = null,
            boundAgentCount: Int = 0,
            boundTeamCount: Int = 0,
            visibility: VisibilitySummary? = null,
        ): SkillResponse {
            if (skill == null) {
                return SkillResponse()
            }
            return SkillResponse(
                id = skill.id,
                name = skill.name,
                repositoryId = skill.repositoryId,
                repositoryName = repository?.name,
                repositoryUrl = repository?.url,
                repositoryBranch = repository?.branch,
                description = skill.description,
                skillmd = skill.skillmd,
                resources = skill.resources,
                status = skill.status,
                boundAgentCount = boundAgentCount,
                boundTeamCount = boundTeamCount,
                isPublic = skill.isPublic,
                creator = skill.creator,
                origin = skill.origin,
                originRef = skill.originRef,
                createTime = skill.createTime,
                updateTime = skill.updateTime,
                visibility = visibility,
            )
        }
    }
}
