package com.vipamp.vipclaw.admin.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull

/**
 * 切换租户请求 DTO
 */
@Schema(description = "切换租户请求对象")
data class SwitchTenantRequest(
    @Schema(description = "租户ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "租户ID不能为空")
    val tenantId: Long,
)
