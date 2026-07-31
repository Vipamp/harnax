package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "Skill source update request")
data class SkillSourceUpdateRequest(
    @Schema(description = "Source name")
    @Size(min = 1, max = 100, message = "Source name length must be between 1-100")
    val name: String? = null,

    @Schema(description = "Source configuration (JSON object)")
    val sourceConfig: Map<String, Any>? = null,

    @Schema(description = "Version identifier")
    val version: String? = null,

    @Schema(description = "Description")
    val description: String? = null,

    @Schema(description = "Status (0:disabled, 1:enabled)")
    val status: Int? = null,

    @Schema(description = "Public status (0:no, 1:yes)")
    val isPublic: Int? = null,

    @Schema(description = "Git URL (for GIT type)")
    val url: String? = null,

    @Schema(description = "Git branch (for GIT type)")
    val branch: String? = null,
)
