package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Full skill configuration returned by admin internal API.
 * Eliminates the need for agent-service to query skill table directly.
 */
@Schema(description = "Skill detail configuration")
data class SkillDetailDto(
    @Schema(description = "Skill ID")
    val id: Long,

    @Schema(description = "Skill name")
    val name: String,

    @Schema(description = "Skill description")
    val description: String = "",

    @Schema(description = "skill.md content")
    val skillmd: String = "",

    @Schema(description = "Resource information (JSON)")
    val resources: String = "",

    @Schema(description = "Skill version")
    val version: String = "",
)
