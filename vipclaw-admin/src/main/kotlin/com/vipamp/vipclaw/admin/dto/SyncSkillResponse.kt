package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 同步技能响应对象
 */
@Schema(description = "同步技能响应对象")
data class SyncSkillResponse(
    @Schema(description = "技能名称", example = "Java 编程助手")
    var name: String? = null,
    @Schema(description = "技能描述", example = "提供 Java 编程相关的技能帮助")
    var description: String? = null,
    @Schema(description = "skill.md 内容")
    var skillmd: String? = null,
    @Schema(description = "资源信息")
    var resources: Map<String, String> = mapOf()
)
