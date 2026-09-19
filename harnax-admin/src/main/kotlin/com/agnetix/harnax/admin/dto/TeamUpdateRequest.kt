package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Size

/**
 * Team update request DTO.
 *
 * Null fields keep their current value, like every other update DTO here. [members] is an exception:
 * a non-null list replaces the whole membership, because a partial member diff has no honest meaning
 * when the caller sends display-level edits and removals in one form.
 */
@Schema(description = "Team update request object")
data class TeamUpdateRequest(
    @Schema(description = "Team name", example = "Research report team")
    @Size(min = 1, max = 100, message = "Team name length must be between 1-100")
    val name: String? = null,

    @Schema(description = "Team description")
    val description: String? = null,

    @Schema(description = "Lead agent ID", example = "1")
    val leadAgentId: Long? = null,

    @Schema(description = "Team instructions appended to the lead prompt")
    val instructions: String? = null,

    @Schema(description = "Member list; replaces the current membership when present")
    @Valid
    val members: List<TeamCreateRequest.MemberItem>? = null,

    @Schema(description = "Is public (0:false 1:true)", example = "0")
    val isPublic: Int? = null,
)
