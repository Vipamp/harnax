package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.SkillRepository
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Skill repository response object
 */
@Schema(description = "Skill repository response object")
data class SkillRepositoryResponse(
    @Schema(description = "ID", example = "1")
    val id: Long? = null,
    @Schema(description = "Repository name", example = "qoder-skills")
    val name: String? = null,
    @Schema(description = "Repository URL", example = "https://github.com/example/skills")
    val url: String? = null,
    @Schema(description = "Branch name", example = "main")
    val branch: String? = null,
    @Schema(description = "Repository description")
    val description: String? = null,
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    val status: Int? = null,
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    val isPublic: Int? = null,
    @Schema(description = "创建人", example = "admin")
    val creator: String? = null,
    @Schema(description = "Creation time", example = "2026-03-16 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "Update time", example = "2026-03-16 12:00:00")
    val updateTime: LocalDateTime? = null,
) {
    companion object {
        @JvmStatic
        fun fromEntity(entity: SkillRepository): SkillRepositoryResponse = SkillRepositoryResponse(
            id = entity.id,
            name = entity.name,
            url = entity.url,
            branch = entity.branch,
            description = entity.description,
            status = entity.status,
            isPublic = entity.isPublic,
            creator = entity.creator,
            createTime = entity.createTime,
            updateTime = entity.updateTime,
        )
    }
}
