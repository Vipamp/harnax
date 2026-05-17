package com.agnetix.harnax.admin.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull

/**
 * Switch tenant request DTO
 */
@Schema(description = "Switch tenant request object")
data class SwitchTenantRequest(
    @Schema(description = "Tenant ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "Tenant ID cannot be empty")
    val tenantId: Long,
)
