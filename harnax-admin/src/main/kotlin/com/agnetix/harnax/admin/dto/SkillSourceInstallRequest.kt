package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Body of `POST /skill-sources/{id}/install`.
 *
 * An absent body and an absent `names` both mean the whole source, so the endpoint that re-installs
 * everything and the one that re-installs a selection stay one route. An empty `names` is the other
 * answer — it says the caller had nothing to store.
 */
@Schema(description = "Skill source install request")
data class SkillSourceInstallRequest(
    @Schema(description = "Skill names to store, as the source declares them. Omit the field to store everything the source holds")
    val names: List<String>? = null,
)
