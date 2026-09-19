package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Team runtime configuration handed to agent-service for one team session.
 *
 * The lead and every member arrive as a complete [AgentSpecInfoResponse], because a member runs with
 * its own tools, MCP servers, skills and CLI set (design D5): sending only `modelId`/`toolId` would
 * assume the lead's adapter can resolve another agent's capabilities, and it cannot.
 *
 * Resolved from the root session only. The child session ids agent-service mints for member runs never
 * reach admin's prefix-based agent-spec endpoint — an unknown id would otherwise be answered with
 * some other agent's configuration.
 */
@Schema(description = "Team runtime spec for agent-service")
data class TeamSpecInfoResponse(
    @Schema(description = "Team ID")
    val teamId: Long,

    @Schema(description = "Owning tenant — the first scope of every artifact publish and read")
    val tenantId: Long,

    @Schema(description = "Team name")
    val teamName: String,

    @Schema(description = "Team instructions appended to the lead prompt")
    val instructions: String = "",

    @Schema(description = "Lead agent configuration, resolved from the team rather than session.agentId")
    val lead: AgentSpecInfoResponse,

    @Schema(description = "Member configurations in assembly order")
    val members: List<TeamMemberSpecDto> = emptyList(),
)

@Schema(description = "One team member with its full configuration")
data class TeamMemberSpecDto(
    @Schema(description = "Member agent ID — the stable key the lead delegates by")
    val memberAgentId: Long,

    @Schema(description = "Member agent name")
    val agentName: String,

    @Schema(description = "Role of this member inside this team")
    val delegationDescription: String = "",

    @Schema(description = "Full configuration of the member agent")
    val spec: AgentSpecInfoResponse,
)
