package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * 技能创建请求对象
 */
@Schema(description = "技能创建请求对象")
data class SkillCreateRequest(
    @Size(min = 1, max = 100, message = "技能名称长度必须在 1-100 之间")
    val name: String? = null,
    @Schema(description = "仓库ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val repositoryId: Long? = null,
    @Schema(description = "技能描述")
    val description: String? = null,
    @Schema(description = "skill.md 内容")
    val skillmd: String? = null,
    @Schema(description = "资源信息")
    val resources: String? = null,
    @Schema(description = "状态 (0:禁用 1:正常)")
    val status: Int? = null,
)
