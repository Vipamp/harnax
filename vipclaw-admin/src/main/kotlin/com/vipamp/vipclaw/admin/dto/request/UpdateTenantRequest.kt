package com.vipamp.vipclaw.admin.dto.request

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 更新租户请求 DTO
 */
@Schema(description = "更新租户请求对象")
data class UpdateTenantRequest(
    @Schema(description = "租户名称", example = "示例公司")
    val name: String? = null,

    @Schema(description = "状态（0:禁用 1:启用）", example = "1")
    val status: Int? = null
)
