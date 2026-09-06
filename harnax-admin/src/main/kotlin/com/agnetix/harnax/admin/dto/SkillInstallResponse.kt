package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Outcome of installing skills from a source.
 *
 * A partial failure used to be swallowed by a log line while the API still returned 200, so the
 * caller had no way to notice missing skills. Every skill now lands in exactly one bucket.
 */
@Schema(description = "Skill install result")
data class SkillInstallResponse(
    @Schema(description = "Names newly inserted")
    val installed: List<String> = emptyList(),

    @Schema(description = "Names overwritten from the source")
    val updated: List<String> = emptyList(),

    @Schema(description = "Names that could not be persisted, with the reason")
    val failed: List<FailedSkill> = emptyList(),

    @Schema(description = "Names stored disabled because the content scan flagged them")
    val flagged: List<FlaggedSkill> = emptyList(),
) {
    @Schema(description = "Number of skills stored (installed + updated)")
    val savedCount: Int = installed.size + updated.size

    @Schema(description = "Number of skills that could not be stored")
    val failedCount: Int = failed.size

    @Schema(description = "Whether every skill from the source was stored")
    val complete: Boolean = failed.isEmpty()

    @Schema(description = "Human readable summary")
    val summary: String = buildString {
        append("$savedCount saved")
        if (installed.isNotEmpty()) append(" (${installed.size} new)")
        if (updated.isNotEmpty()) append(" (${updated.size} updated)")
        if (flagged.isNotEmpty()) append(", ${flagged.size} disabled pending review")
        if (failed.isNotEmpty()) append(", ${failed.size} failed")
    }

    @Schema(description = "A skill that could not be persisted")
    data class FailedSkill(
        @Schema(description = "Skill name")
        val name: String,

        @Schema(description = "Failure reason")
        val reason: String,
    )

    @Schema(description = "A skill stored in disabled state after a content scan hit")
    data class FlaggedSkill(
        @Schema(description = "Skill name")
        val name: String,

        @Schema(description = "Reasons the content was flagged")
        val reasons: List<String>,
    )
}
