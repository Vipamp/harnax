package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * Skill creation request object
 */
@Schema(description = "Skill creation request object")
data class SkillCreateRequest(
    @Size(min = 1, max = 100, message = "Skill name length must be between 1-100")
    val name: String? = null,
    @Schema(description = "Repository ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val repositoryId: Long? = null,
    @Schema(description = "Skill description")
    val description: String? = null,
    @Schema(description = "skill.md content")
    val skillmd: String? = null,
    @Schema(description = "Resource information")
    val resources: String? = null,
    @Schema(description = "Status (0:disabled 1:enabled)")
    val status: Int? = null,
)
