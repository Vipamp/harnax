package com.vipamp.vipclaw.admin.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 租户响应 DTO
 */
@Schema(description = "租户响应对象")
data class TenantResponse(
    @Schema(description = "租户ID", example = "1")
    val id: Long? = null,

    @Schema(description = "租户名称", example = "示例公司")
    val name: String? = null,

    @Schema(description = "状态（0:禁用 1:启用）", example = "1")
    val status: Int? = null,

    @Schema(description = "创建人", example = "admin")
    val creator: String? = null,

    @Schema(description = "创建时间", example = "2026-04-28T10:00:00")
    val createTime: LocalDateTime? = null,

    @Schema(description = "更新时间", example = "2026-04-28T10:00:00")
    val updateTime: LocalDateTime? = null,
)
