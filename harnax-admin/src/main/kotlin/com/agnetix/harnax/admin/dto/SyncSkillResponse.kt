package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Sync skill response object
 */
@Schema(description = "Sync skill response object")
data class SyncSkillResponse(
    @Schema(description = "Skill name", example = "Java Programming Assistant")
    var name: String? = null,
    @Schema(description = "Skill description", example = "Provides skill assistance related to Java programming")
    var description: String? = null,
    @Schema(description = "skill.md content")
    var skillmd: String? = null,
    @Schema(description = "Resource information")
    var resources: Map<String, String> = mapOf(),
    @Schema(description = "Whether the skill already exists in the repository")
    var exists: Boolean = false,
)
