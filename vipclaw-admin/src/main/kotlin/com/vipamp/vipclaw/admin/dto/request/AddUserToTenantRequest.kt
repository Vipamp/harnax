package com.vipamp.vipclaw.admin.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * 添加用户到租户请求 DTO
 */
@Schema(description = "添加用户到租户请求对象")
data class AddUserToTenantRequest(
    @Schema(description = "用户ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "用户ID不能为空")
    val userId: Long,

    @Schema(description = "角色（admin/member）", example = "member")
    @field:NotBlank(message = "角色不能为空")
    val role: String = "member",
)
