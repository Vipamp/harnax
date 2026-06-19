package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * Skill repository management controller
 * Available only for public edition (skill marketplace feature)
 */
@RestController
@RequestMapping("/api/skill-repositories")
@Tag(name = "Skill Repository Management", description = "Skill repository related APIs")
class SkillRepositoryController(
    private val skillRepositoryService: SkillRepositoryService,
) {

    private val log = LoggerFactory.getLogger(SkillRepositoryController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get skill repository list with pagination", description = "Paginated query for skill repository information")
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
        ResultVo.error(e.message ?: "Failed to get skill repository list")
    }

    @GetMapping("/active")
    @Operation(summary = "Get all active repositories", description = "Get list of all active repositories")
    fun getActiveRepositories(): ResultVo<List<SkillRepositoryResponse>> = try {
        val repositories = skillRepositoryService.getActiveRepositories()
        val responseList = repositories.map { SkillRepositoryResponse.fromEntity(it) }
        ResultVo.success(responseList)
    } catch (e: Exception) {
        log.error("Failed to get active repositories", e)
        ResultVo.error(e.message ?: "Failed to get active repositories")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get skill repository details", description = "Get skill repository information by skill repository ID")
    fun getSkillRepository(
        @Parameter(description = "Skill repository ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<SkillRepositoryResponse?> = try {
        val repository = skillRepositoryService.getSkillRepository(id)
        ResultVo.success(repository?.let { SkillRepositoryResponse.fromEntity(it) })
    } catch (e: Exception) {
        log.error("Failed to get skill repository details", e)
        ResultVo.error(e.message ?: "Failed to get skill repository details")
    }

    @PostMapping
    @Operation(summary = "Create skill repository", description = "Add new skill repository information")
    fun createRepository(
        @Valid @RequestBody request: SkillRepositoryCreateRequest,
    ): ResultVo<Void> = try {
        if (skillRepositoryService.createSkillRepository(request)) ResultVo.success() else ResultVo.error("Failed to create skill repository")
    } catch (e: Exception) {
        log.error("Failed to create skill repository", e)
        ResultVo.error(e.message ?: "Failed to create skill repository")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update skill repository", description = "Update skill repository information by skill repository ID")
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
        ResultVo.error(e.message ?: "Failed to update skill repository")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle skill repository status", description = "Toggle skill repository status by skill repository ID")
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
            ResultVo.error("Failed to update skill repository")
        }
    } catch (e: Exception) {
        log.error("Failed to update skill repository", e)
        ResultVo.error(e.message ?: "Failed to update skill repository")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete skill repository", description = "Delete skill repository by skill repository ID")
    fun deleteSkillRepository(
        @Parameter(description = "Skill repository ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (skillRepositoryService.deleteSkillRepository(id)) ResultVo.success() else ResultVo.error("Failed to delete skill repository")
    } catch (e: Exception) {
        log.error("Failed to delete skill repository", e)
        ResultVo.error(e.message ?: "Failed to delete skill repository")
    }

    @GetMapping("/fetch/{id}")
    @Operation(summary = "Get remote skill list", description = "Get syncable skills from remote repository")
    fun fetchRemoteSkills(
        @Parameter(description = "Skill repository ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<List<SyncSkillResponse>> = try {
        val skills = skillRepositoryService.fetchRemoteSkills(id)
        ResultVo.success(skills)
    } catch (e: Exception) {
        log.error("Failed to get remote skill list", e)
        ResultVo.error(e.message ?: "Failed to get remote skill list")
    }
}
