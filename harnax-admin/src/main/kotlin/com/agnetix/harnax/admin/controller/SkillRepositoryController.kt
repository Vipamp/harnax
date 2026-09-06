package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.util.ApiErrors
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * Skill repository management controller (legacy entry point).
 *
 * Every handler is deprecated in favour of `/api/admin/skill-sources`. The endpoints stay served so
 * external integrations keep working, and each one answers through the same service the new API
 * uses. Nothing inside this repository calls them any more: `harnax-cli`, the webui and the mini
 * program all went over to `skill-sources`.
 */
@RestController
@RequestMapping("/api/admin/skill-repositories")
@Tag(name = "Skill Repository Management", description = "Skill repository related APIs")
class SkillRepositoryController(
    private val skillRepositoryService: SkillRepositoryService,
) {

    private val log = LoggerFactory.getLogger(SkillRepositoryController::class.java)

    @GetMapping("/page")
    @Deprecated("Use GET /api/admin/skill-sources/page")
    @Operation(
        summary = "Get skill repository list with pagination",
        description = "Paginated query for skill repository information",
        deprecated = true,
    )
    fun pageSkillRepository(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "Repository name") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "Status filter") @RequestParam(name = "status", required = false) status: Int?,
    ): ResultVo<Page<SkillRepositoryResponse>> = try {
        val page = skillRepositoryService.page(
            name,
            status,
            pageNum ?: 1,
            pageSize ?: 10,
        )
        ResultVo.success(page.mapRecords { SkillRepositoryResponse.fromEntity(it) })
    } catch (e: Exception) {
        log.error("Failed to get skill repository list", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get skill repository list"))
    }

    @GetMapping("/active")
    @Deprecated("Use GET /api/admin/skill-sources/active")
    @Operation(
        summary = "Get all active repositories",
        description = "Get list of all active repositories",
        deprecated = true,
    )
    fun getActiveRepositories(): ResultVo<List<SkillRepositoryResponse>> = try {
        val repositories = skillRepositoryService.getActiveRepositories()
        val responseList = repositories.map { SkillRepositoryResponse.fromEntity(it) }
        ResultVo.success(responseList)
    } catch (e: Exception) {
        log.error("Failed to get active repositories", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get active repositories"))
    }

    @GetMapping("/{id}")
    @Deprecated("Use GET /api/admin/skill-sources/{id}")
    @Operation(
        summary = "Get skill repository details",
        description = "Get skill repository information by skill repository ID",
        deprecated = true,
    )
    fun getSkillRepository(
        @Parameter(description = "Skill repository ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<SkillRepositoryResponse?> = try {
        val repository = skillRepositoryService.getSkillRepository(id)
        ResultVo.success(repository?.let { SkillRepositoryResponse.fromEntity(it) })
    } catch (e: Exception) {
        log.error("Failed to get skill repository details", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get skill repository details"))
    }

    @PostMapping
    @Deprecated("Use POST /api/admin/skill-sources")
    @Operation(
        summary = "Create skill repository",
        description = "Add new skill repository information",
        deprecated = true,
    )
    fun createRepository(
        @Valid @RequestBody request: SkillRepositoryCreateRequest,
    ): ResultVo<Void> = try {
        if (skillRepositoryService.createSkillRepository(request)) ResultVo.success() else ResultVo.error("Failed to create skill repository")
    } catch (e: Exception) {
        log.error("Failed to create skill repository", e)
        ResultVo.error(ApiErrors.message(e, "Failed to create skill repository"))
    }

    @PutMapping("/update/{id}")
    @Deprecated("Use PUT /api/admin/skill-sources/{id}")
    @Operation(
        summary = "Update skill repository",
        description = "Update skill repository information by skill repository ID",
        deprecated = true,
    )
    fun updateSkillRepository(
        @Parameter(description = "Skill repository ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: SkillRepositoryUpdateRequest,
    ): ResultVo<Void> = try {
        if (skillRepositoryService.updateSkillRepository(
                id,
                request,
            )
        ) {
            ResultVo.success()
        } else {
            ResultVo.error("Failed to update skill repository")
        }
    } catch (e: Exception) {
        log.error("Failed to update skill repository", e)
        ResultVo.error(ApiErrors.message(e, "Failed to update skill repository"))
    }

    @PutMapping("/toggle/{id}")
    @Deprecated("Use PUT /api/admin/skill-sources/toggle/{id}")
    @Operation(
        summary = "Toggle skill repository status",
        description = "Toggle skill repository status by skill repository ID",
        deprecated = true,
    )
    fun toggleSkillRepository(
        @Parameter(description = "Skill repository ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Skill repository status") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (skillRepositoryService.toggleSkillRepository(
                id,
                status,
            )
        ) {
            ResultVo.success()
        } else {
            ResultVo.error("Failed to toggle skill repository status")
        }
    } catch (e: Exception) {
        // Both the log line and the failure text said "update", so a rejected toggle pointed at the
        // wrong handler and at the wrong operation
        log.error("Failed to toggle skill repository status", e)
        ResultVo.error(ApiErrors.message(e, "Failed to toggle skill repository status"))
    }

    @DeleteMapping("/{id}")
    @Deprecated("Use DELETE /api/admin/skill-sources/{id}")
    @Operation(
        summary = "Delete skill repository",
        description = "Delete skill repository by skill repository ID",
        deprecated = true,
    )
    fun deleteSkillRepository(
        @Parameter(description = "Skill repository ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (skillRepositoryService.deleteSkillRepository(id)) ResultVo.success() else ResultVo.error("Failed to delete skill repository")
    } catch (e: Exception) {
        log.error("Failed to delete skill repository", e)
        ResultVo.error(ApiErrors.message(e, "Failed to delete skill repository"))
    }

    @GetMapping("/fetch/{id}")
    @Deprecated("Use GET /api/admin/skill-sources/{id}/fetch")
    @Operation(
        summary = "Get remote skill list",
        description = "Get syncable skills from remote repository",
        deprecated = true,
    )
    fun fetchRemoteSkills(
        @Parameter(description = "Skill repository ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<List<SyncSkillResponse>> = try {
        val skills = skillRepositoryService.fetchRemoteSkills(id)
        ResultVo.success(skills)
    } catch (e: Exception) {
        log.error("Failed to get remote skill list", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get remote skill list"))
    }
}
