package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillCreateRequest
import com.agnetix.harnax.admin.dto.SkillResponse
import com.agnetix.harnax.admin.dto.SkillUpdateRequest
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * Skill management controller
 * Available only for public edition (skill marketplace feature)
 */
@RestController
@RequestMapping("/api/skills")
@Tag(name = "Skill Management", description = "Skill related APIs")
class SkillController(
    private val skillService: SkillService,
    private val skillRepositoryService: SkillRepositoryService,
) {

    private val log = LoggerFactory.getLogger(SkillController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get skill list with pagination", description = "Paginated query for skill information")
    fun pageSkill(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "Skill name") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "Repository ID") @RequestParam(name = "repositoryId", required = false) repositoryId: Long?,
        @Parameter(description = "Status filter") @RequestParam(name = "status", required = false) status: Int?,
    ): ResultVo<Page<SkillResponse>> = try {
        val page = skillService.page(name, repositoryId, status, pageNum ?: 1, pageSize ?: 10)
        val responsePage = page.mapRecords { skillService.convertToResponse(it) }
        ResultVo.success(responsePage)
    } catch (e: Exception) {
        log.error("Failed to get skill list", e)
        ResultVo.error(e.message ?: "Failed to get skill list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get skill details", description = "Get skill information by skill ID")
    fun getSkill(
        @Parameter(description = "Skill ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<SkillResponse?> = try {
        val skill = skillService.getSkill(id)
        // Get repository information
        val repository = skillRepositoryService.getSkillRepository(skill!!.repositoryId)
        ResultVo.success(SkillResponse.fromEntity(skill, repository))
    } catch (e: Exception) {
        log.error("Failed to get skill details", e)
        ResultVo.error(e.message ?: "Failed to get skill details")
    }

    @PostMapping
    @Operation(summary = "Create skill", description = "Add new skill information")
    fun createSkill(
        @Valid @RequestBody request: SkillCreateRequest,
    ): ResultVo<Void> = try {
        if (skillService.createSkill(request)) ResultVo.success() else ResultVo.error("Failed to create skill")
    } catch (e: Exception) {
        log.error("Failed to create skill", e)
        ResultVo.error(e.message ?: "Failed to create skill")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update skill", description = "Update skill information by skill ID")
    fun updateSkill(
        @Parameter(description = "Skill ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: SkillUpdateRequest,
    ): ResultVo<Void> = try {
        if (skillService.updateSkill(id, request)) ResultVo.success() else ResultVo.error("Failed to update skill")
    } catch (e: Exception) {
        log.error("Failed to update skill", e)
        ResultVo.error(e.message ?: "Failed to update skill")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle skill status", description = "Toggle skill status by skill ID")
    fun toggleSkill(
        @Parameter(description = "Skill ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Skill status") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (skillService.toggleSkillStatus(id, status)) ResultVo.success() else ResultVo.error("Failed to toggle skill status")
    } catch (e: Exception) {
        log.error("Failed to update skill", e)
        ResultVo.error(e.message ?: "Failed to update skill")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete skill", description = "Delete skill by skill ID")
    fun deleteSkill(
        @Parameter(description = "Skill ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (skillService.deleteSkill(id)) ResultVo.success() else ResultVo.error("Failed to delete skill")
    } catch (e: Exception) {
        log.error("Failed to delete skill", e)
        ResultVo.error(e.message ?: "Failed to delete skill")
    }

    @PostMapping("/batch")
    @Operation(summary = "Batch save skills", description = "Batch save skills to specified repository, duplicate skills will be overwritten")
    fun batchSaveSkills(
        @Parameter(description = "Repository ID") @RequestParam(name = "repositoryId") repositoryId: Long,
        @Parameter(description = "Skill list") @RequestBody skills: List<String>,
    ): ResultVo<Int> = try {
        val count = skillService.batchSaveSkills(repositoryId, skills)
        ResultVo.success(count)
    } catch (e: Exception) {
        log.error("Failed to batch save skills", e)
        ResultVo.error(e.message ?: "Failed to batch save skills")
    }
}
