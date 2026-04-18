package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.SkillRepository
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 技能仓库响应对象
 */
@Schema(description = "技能仓库响应对象")
data class SkillRepositoryResponse(
    @Schema(description = "ID", example = "1")
    val id: Long? = null,
    @Schema(description = "仓库名称", example = "qoder-skills")
    val name: String? = null,
    @Schema(description = "仓库地址", example = "https://github.com/example/skills")
    val url: String? = null,
    @Schema(description = "分支名称", example = "main")
    val branch: String? = null,
    @Schema(description = "仓库描述")
    val description: String? = null,
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    val status: Int? = null,
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    val isPublic: Int? = null,
    @Schema(description = "创建人", example = "admin")
    val creator: String? = null,
    @Schema(description = "创建时间", example = "2026-03-16 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "更新时间", example = "2026-03-16 12:00:00")
    val updateTime: LocalDateTime? = null
) {
    companion object {
        @JvmStatic
        fun fromEntity(entity: SkillRepository?): SkillRepositoryResponse {
            if (entity == null) return SkillRepositoryResponse()
            return SkillRepositoryResponse(
                id = entity.id,
                name = entity.name,
                url = entity.url,
                branch = entity.branch,
                description = entity.description,
                status = entity.status,
                isPublic = entity.isPublic,
                creator = entity.creator,
                createTime = entity.createTime,
                updateTime = entity.updateTime
            )
        }
    }
}
