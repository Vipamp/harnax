package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.TeamCreateRequest
import com.agnetix.harnax.admin.dto.TeamResponse
import com.agnetix.harnax.admin.dto.TeamUpdateRequest
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.service.TeamService
import com.agnetix.harnax.admin.service.impl.RelatedSessionInfo
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * Team management controller.
 * A team only references existing agents; capabilities stay on the Agent pages.
 */
@RestController
@RequestMapping("/api/admin/teams")
@Tag(name = "Team Management", description = "Multi-agent team related APIs")
class TeamController(
    private val teamService: TeamService,
) {

    private val log = LoggerFactory.getLogger(TeamController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get team list with pagination", description = "Paginated query for team information")
    fun pageTeam(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "Team name") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "Status filter") @RequestParam(name = "status", required = false) status: Int?,
    ): ResultVo<Page<TeamResponse>> = try {
        val page = teamService.page(name, status, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { teamService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get team list", e)
        ResultVo.error(e.message ?: "Failed to get team list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get team details", description = "Get team information by team ID")
    fun getTeam(
        @Parameter(description = "Team ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<TeamResponse?> = try {
        val team = teamService.getTeam(id)
        ResultVo.success(team?.let { teamService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get team details", e)
        ResultVo.error(e.message ?: "Failed to get team details")
    }

    @PostMapping
    @Operation(summary = "Create team", description = "Add a new team from an existing lead agent and member agents")
    fun createTeam(
        @Valid @RequestBody request: TeamCreateRequest,
    ): ResultVo<Void> = try {
        if (teamService.createTeam(request)) ResultVo.success() else ResultVo.error("Failed to create team")
    } catch (e: Exception) {
        log.error("Failed to create team", e)
        ResultVo.error(e.message ?: "Failed to create team")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update team", description = "Update team information by team ID")
    fun updateTeam(
        @Parameter(description = "Team ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: TeamUpdateRequest,
    ): ResultVo<Void> = try {
        if (teamService.updateTeam(id, request)) ResultVo.success() else ResultVo.error("Failed to update team")
    } catch (e: Exception) {
        log.error("Failed to update team", e)
        ResultVo.error(e.message ?: "Failed to update team")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle team status", description = "Toggle team status by team ID")
    fun toggleTeam(
        @Parameter(description = "Team ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Team status") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (teamService.toggleTeamStatus(id, status)) ResultVo.success() else ResultVo.error("Failed to toggle team status")
    } catch (e: Exception) {
        log.error("Failed to toggle team status", e)
        ResultVo.error(e.message ?: "Failed to toggle team status")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete team", description = "Delete team by team ID")
    fun deleteTeam(
        @Parameter(description = "Team ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (teamService.deleteTeam(id)) ResultVo.success() else ResultVo.error("Failed to delete team")
    } catch (e: Exception) {
        log.error("Failed to delete team", e)
        ResultVo.error(e.message ?: "Failed to delete team")
    }

    @GetMapping("/{id}/related-sessions")
    @Operation(
        summary = "List sessions bound to a team",
        description = "Team sessions still holding the previous member configuration; push REFRESH via /api/admin/agents/refresh-sessions",
    )
    fun relatedSessions(
        @Parameter(description = "Team ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<List<RelatedSessionInfo>> = try {
        ResultVo.success(teamService.listRelatedSessions(id))
    } catch (e: Exception) {
        log.error("Failed to list sessions for team {}", id, e)
        ResultVo.error(e.message ?: "Failed to list related sessions")
    }
}
