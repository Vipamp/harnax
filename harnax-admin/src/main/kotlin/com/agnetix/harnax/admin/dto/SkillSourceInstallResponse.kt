package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A skill source together with the outcome of the install that ran alongside it.
 *
 * Creating or re-installing a source used to answer with the repository only, which hid
 * per-skill failures behind a 200 response.
 */
@Schema(description = "Skill source with its install result")
data class SkillSourceInstallResponse(
    @Schema(description = "The skill source")
    val source: SkillSourceResponse,

    @Schema(description = "What the install did")
    val install: SkillInstallResponse,
)
