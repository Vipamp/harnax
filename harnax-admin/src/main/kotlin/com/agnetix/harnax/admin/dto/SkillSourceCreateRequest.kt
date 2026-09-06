package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "Skill source create request")
data class SkillSourceCreateRequest(
    @Schema(description = "Source name")
    // `@field:` is what makes these limits real. On a Kotlin data class a bare annotation lands on
    // the constructor parameter, which bean validation never reads, so the declared bound existed
    // in the OpenAPI doc only and an over-long value reached MySQL
    @field:Size(min = 1, max = 100, message = "Source name length must be between 1-100")
    val name: String = "",

    @Schema(description = "Source type: GIT | NPM. A ZIP source is created by POST /api/admin/skill-sources/upload")
    val sourceType: String = "GIT",

    @Schema(description = "Source configuration (JSON object)")
    val sourceConfig: Map<String, Any> = emptyMap(),

    @Schema(description = "Version identifier")
    // Copied onto every installed skill, and both `skill_repository.version` and `skill.version`
    // are varchar(100)
    @field:Size(max = 100, message = "Version length cannot exceed 100")
    val version: String? = null,

    @Schema(description = "Description")
    val description: String = "",

    @Schema(description = "Status (0:disabled, 1:enabled)")
    val status: Int? = null,

    @Schema(description = "Public status (0:no, 1:yes)")
    val isPublic: Int? = null,

    @Schema(description = "Git URL (for GIT type, backward compat)")
    val url: String = "",

    @Schema(description = "Git branch (for GIT type, backward compat)")
    val branch: String = "",
)
