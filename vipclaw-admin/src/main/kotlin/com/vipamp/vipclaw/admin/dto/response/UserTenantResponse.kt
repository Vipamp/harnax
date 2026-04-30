package com.vipamp.vipclaw.admin.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 用户-租户关联响应 DTO
 */
@Schema(description = "用户-租户关联响应对象")
data class UserTenantResponse(
    @Schema(description = "关联ID", example = "1")
    val id: Long? = null,

    @Schema(description = "用户ID", example = "1")
    val userId: Long? = null,

    @Schema(description = "用户名", example = "admin")
    val username: String? = null,

    @Schema(description = "昵称", example = "管理员")
    val nickname: String? = null,

    @Schema(description = "租户ID", example = "1")
    val tenantId: Long? = null,

    @Schema(description = "租户名称", example = "示例公司")
    val tenantName: String? = null,

    @Schema(description = "角色（admin/member）", example = "admin")
    val role: String? = null,

    @Schema(description = "状态（0:禁用 1:启用）", example = "1")
    val status: Int? = null,

    @Schema(description = "加入时间", example = "2026-04-28T10:00:00")
    val joinedAt: LocalDateTime? = null
)
