package com.vipamp.vipclaw.admin.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * Create tenant request DTO
 */
@Schema(description = "Create tenant request object")
data class CreateTenantRequest(
    @Schema(description = "Tenant name", example = "Example Company", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "Tenant name cannot be empty")
    val name: String,

    @Schema(description = "Tenant administrator user ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "Tenant administrator user ID cannot be empty")
    val adminUserId: Long,
)
