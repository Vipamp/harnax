package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * Skill update request object
 */
@Schema(description = "Skill update request object")
data class SkillUpdateRequest(
    @Schema(description = "ID")
    val id: Long? = null,
    @Schema(description = "Skill name")
    // `@field:` is what makes the limit real: a bare annotation on a Kotlin data class lands on the
    // constructor parameter, which bean validation never reads. `SkillCreateRequest` already spells
    // it out, so a rename was the one path where an over-long name reached MySQL unchecked
    @field:Size(min = 1, max = 100, message = "Skill name length must be between 1-100")
    val name: String? = null,
    @Schema(description = "Repository ID", example = "1")
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
