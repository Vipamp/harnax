package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.Skill
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Skill usage over one reporting window, for the operations page.
 *
 * Every row of [rows] is a skill the caller's tenant can see, including the ones with no events at all.
 * That is deliberate: the question this page exists to answer is which skills are never loaded, and an
 * aggregate that grouped over the event stream would simply omit them — an omitted row reads as
 * "unknown" while a zero row reads as "unused", and only the second one is actionable.
 */
@Schema(description = "Skill usage summary for one window")
data class SkillUsageSummaryResponse(
    @Schema(description = "Window length in days", example = "30")
    val days: Int = 0,

    @Schema(description = "Start of the window the counts were taken from")
    val since: LocalDateTime? = null,

    @Schema(description = "Skills the caller's tenant can see, used or not")
    val totalSkills: Int = 0,

    @Schema(description = "Of those, how many had neither a load nor a use inside the window")
    val zeroUseCount: Int = 0,

    @Schema(description = "Loads across all rows")
    val totalViews: Int = 0,

    @Schema(description = "Uses across all rows")
    val totalUses: Int = 0,

    @Schema(description = "Every skill with its counts, most-loaded first")
    val rows: List<Row> = emptyList(),
) {
    /**
     * One skill. [lastUsedAt] is null when the window holds no event for it, and [origin] tells a reader
     * whether an agent wrote the skill — the two facts that separate "nobody ever loaded this" from
     * "this was promoted yesterday".
     */
    @Schema(description = "Usage of one skill inside the window")
    data class Row(
        @Schema(description = "Skill id", example = "1")
        val skillId: Long = 0,

        @Schema(description = "Skill name", example = "web-search")
        val name: String = "",

        @Schema(description = "Repository id", example = "1")
        val repositoryId: Long = 0,

        @Schema(description = "Repository name", example = "qoder-skills")
        val repositoryName: String? = null,

        @Schema(description = "Skill status (0:disabled, 1:enabled)", example = "1")
        val status: Int = 1,

        @Schema(description = "Provenance (human / agent_promoted)", example = "human")
        val origin: String = Skill.ORIGIN_HUMAN,

        @Schema(description = "Times loaded into a context within the window", example = "12")
        val viewCount: Int = 0,

        @Schema(description = "Times its instructions were followed within the window", example = "3")
        val useCount: Int = 0,

        @Schema(description = "Most recent event of either kind within the window")
        val lastUsedAt: LocalDateTime? = null,
    )
}
