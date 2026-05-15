package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.Skill
import com.vipamp.vipclaw.admin.entity.SkillRepository
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
    @Schema(description = "Whether public (0:no, 1:yes)", example = "1")
    val isPublic: Int? = null,
    @Schema(description = "Creator", example = "admin")
    val creator: String? = null,
    @Schema(description = "Creation time", example = "2026-03-16 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "Update time", example = "2026-03-16 12:00:00")
    val updateTime: LocalDateTime? = null,
) {
    companion object {
        /**
         * Convert Skill entity to SkillResponse
         */
        @JvmStatic
        fun fromEntity(
            skill: Skill?,
            repository: SkillRepository? = null,
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
                isPublic = skill.isPublic,
                creator = skill.creator,
                createTime = skill.createTime,
                updateTime = skill.updateTime,
            )
        }
    }
}
