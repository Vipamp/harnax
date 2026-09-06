package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * Skill repository creation request object
 */
@Schema(description = "Skill repository creation request object")
data class SkillRepositoryCreateRequest(
    // `@field:` is what makes these limits real. On a Kotlin data class a bare annotation lands on
    // the constructor parameter, which bean validation never reads, so the declared bound existed
    // in the OpenAPI doc only and an over-long value reached MySQL
    @field:Size(min = 1, max = 100, message = "Repository name length must be between 1-100")
    val name: String = "",
    @Schema(description = "Repository URL")
    @field:Size(max = 500, message = "Repository URL length cannot exceed 500")
    val url: String = "",
    @Schema(description = "Branch name")
    @field:Size(max = 100, message = "Branch name length cannot exceed 100")
    val branch: String = "",
    @Schema(description = "Repository description")
    val description: String = "",
    @Schema(description = "Status (0:disabled 1:enabled)")
    val status: Int = 1,
)
