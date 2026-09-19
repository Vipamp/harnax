package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Team creation request DTO
 */
@Schema(description = "Team creation request object")
data class TeamCreateRequest(
    @Schema(description = "Team name", example = "Research report team", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Team name cannot be empty")
    @Size(min = 1, max = 100, message = "Team name length must be between 1-100")
    val name: String? = null,

    @Schema(description = "Team description")
    val description: String? = null,

    @Schema(description = "Lead agent ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Lead agent ID cannot be empty")
    val leadAgentId: Long? = null,

    @Schema(description = "Team instructions appended to the lead prompt")
    val instructions: String? = null,

    @Schema(description = "Member list", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "A team needs at least one member")
    @Valid
    val members: List<MemberItem>? = null,

    @Schema(description = "Status (0:disabled 1:enabled)", example = "1")
    val status: Int? = null,

    @Schema(description = "Is public (0:false 1:true)", example = "0")
    val isPublic: Int? = null,
) {
    /**
     * One member reference: an existing agent plus what it is responsible for in this team.
     */
    @Schema(description = "Team member item")
    data class MemberItem(
        @Schema(description = "Member agent ID", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Member agent ID cannot be empty")
        val agentId: Long? = null,

        @Schema(description = "Delegation description within this team")
        @Size(max = 500, message = "Delegation description cannot exceed 500 characters")
        val delegationDescription: String? = null,
    )
}
