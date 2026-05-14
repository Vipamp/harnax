package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * Skill repository update request object
 */
@Schema(description = "Skill repository update request object")
data class SkillRepositoryUpdateRequest(
    @Schema(description = "ID")
    val id: Long? = null,
    @Schema(description = "Repository name")
    @Size(min = 1, max = 100, message = "Repository name length must be between 1-100")
    val name: String? = null,
    @Schema(description = "Repository URL")
    @Size(max = 500, message = "Repository URL length cannot exceed 500")
    val url: String? = null,
    @Schema(description = "Branch name")
    @Size(max = 100, message = "Branch name length cannot exceed 100")
    val branch: String? = null,
    @Schema(description = "Repository description")
    val description: String? = null,
    @Schema(description = "Status (0:disabled 1:enabled)")
    val status: Int? = null,
)
