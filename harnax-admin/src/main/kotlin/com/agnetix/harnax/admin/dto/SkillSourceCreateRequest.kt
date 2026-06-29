package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "Skill source create request")
data class SkillSourceCreateRequest(
    @Schema(description = "Source name")
    @Size(min = 1, max = 100, message = "Source name length must be between 1-100")
    val name: String = "",

    @Schema(description = "Source type: GIT | NPM | ZIP")
    val sourceType: String = "GIT",

    @Schema(description = "Source configuration (JSON object)")
    val sourceConfig: Map<String, Any> = emptyMap(),

    @Schema(description = "Version identifier")
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
