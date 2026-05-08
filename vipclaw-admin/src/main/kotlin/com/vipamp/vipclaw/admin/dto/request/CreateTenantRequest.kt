package com.vipamp.vipclaw.admin.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * 创建租户请求 DTO
 */
@Schema(description = "创建租户请求对象")
data class CreateTenantRequest(
    @Schema(description = "租户名称", example = "示例公司", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "租户名称不能为空")
    val name: String,

    @Schema(description = "租户管理员用户ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "租户管理员用户ID不能为空")
    val adminUserId: Long,
)
