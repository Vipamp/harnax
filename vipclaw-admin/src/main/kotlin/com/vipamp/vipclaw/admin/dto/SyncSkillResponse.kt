package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * 同步技能响应对象
 */
@Schema(description = "同步技能响应对象")
data class SyncSkillResponse(
    @Schema(description = "技能名称", example = "Java 编程助手")
    val name: String? = null,
    @Schema(description = "技能描述", example = "提供 Java 编程相关的技能帮助")
    val description: String? = null,
    @Schema(description = "skill.md 内容")
    val skillmd: String? = null,
    @Schema(description = "资源信息")
    val resources: String? = null,
    @Schema(description = "是否已存在（true: 已存在，false: 不存在）", example = "false")
    val exists: Boolean? = null
)
