package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * 技能仓库创建请求对象
 */
@Schema(description = "技能仓库创建请求对象")
data class SkillRepositoryCreateRequest(
    @Size(min = 1, max = 100, message = "仓库名称长度必须在 1-100 之间")
    val name: String = "",
    @Schema(description = "仓库地址")
    @Size(max = 500, message = "仓库地址长度不能超过 500")
    val url: String = "",
    @Schema(description = "分支名称")
    @Size(max = 100, message = "分支名称长度不能超过 100")
    val branch: String = "",
    @Schema(description = "仓库描述")
    val description: String = "",
    @Schema(description = "状态 (0:禁用 1:正常)")
    val status: Int = 1,
)
