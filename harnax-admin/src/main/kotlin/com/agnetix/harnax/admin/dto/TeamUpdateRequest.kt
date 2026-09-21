package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Size

/**
 * Team update request DTO.
 *
 * Null fields keep their current value, like every other update DTO here. [members] and [skillIds] are
 * exceptions: a non-null list replaces the whole set, because a partial diff has no honest meaning when
 * the caller sends display-level edits and removals in one form. An empty [skillIds] therefore means
 * "give the lead no skill", which null does not.
 */
@Schema(description = "Team update request object")
data class TeamUpdateRequest(
    @Schema(description = "Team name", example = "Research report team")
    @Size(min = 1, max = 100, message = "Team name length must be between 1-100")
    val name: String? = null,

    @Schema(description = "Team description")
    val description: String? = null,

    @Schema(description = "System prompt of the lead (Markdown supported)")
    val systemPrompt: String? = null,

    @Schema(description = "Chat model ID of the lead", example = "1")
    val modelId: Long? = null,

    @Schema(description = "Skill ID list for the lead; replaces the current set when present")
    val skillIds: List<Long>? = null,

    @Schema(description = "Member list; replaces the current membership when present")
    @Valid
    val members: List<TeamCreateRequest.MemberItem>? = null,

    @Schema(description = "Is public (0:false 1:true)", example = "0")
    val isPublic: Int? = null,
)
