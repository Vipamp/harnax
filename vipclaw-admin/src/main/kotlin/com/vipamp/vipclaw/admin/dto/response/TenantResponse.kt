package com.vipamp.vipclaw.admin.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Tenant response DTO
 */
@Schema(description = "Tenant response object")
data class TenantResponse(
    @Schema(description = "Tenant ID", example = "1")
    val id: Long? = null,

    @Schema(description = "Tenant name", example = "Example Company")
    val name: String? = null,

    @Schema(description = "Status (0:disabled 1:enabled)", example = "1")
    val status: Int? = null,

    @Schema(description = "Creator", example = "admin")
    val creator: String? = null,

    @Schema(description = "Creation time", example = "2026-04-28T10:00:00")
    val createTime: LocalDateTime? = null,

    @Schema(description = "Update time", example = "2026-04-28T10:00:00")
    val updateTime: LocalDateTime? = null,
)
