package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.SkillCreateRequest
import com.vipamp.vipclaw.admin.dto.SkillResponse
import com.vipamp.vipclaw.admin.dto.SkillUpdateRequest
import com.vipamp.vipclaw.admin.service.SkillRepositoryService
import com.vipamp.vipclaw.admin.service.SkillService
import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.common.page.mapRecords
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * 技能管理控制器
 *
 * @author vipamp
 * @since 2026-03-16
 */
@RestController
@RequestMapping("/api/skills")
@Tag(name = "技能管理", description = "技能相关接口")
class SkillController(
    private val skillService: SkillService,
    private val skillRepositoryService: SkillRepositoryService
) {

    private val log = LoggerFactory.getLogger(SkillController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取技能列表", description = "分页查询技能信息")
    fun pageSkill(
        @Parameter(description = "页码", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1"
        ) pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10"
        ) pageSize: Int?,
        @Parameter(description = "技能名称") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "仓库ID") @RequestParam(name = "repositoryId", required = false) repositoryId: Long?,
        @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) status: Int?
    ): ResultVo<Page<SkillResponse>> {
        return try {
            val page = skillService.page(name, repositoryId, status, pageNum ?: 1, pageSize ?: 10)
            val responsePage = page.mapRecords { skillService.convertToResponse(it) }
            ResultVo.success(responsePage)
        } catch (e: Exception) {
            log.error("获取技能列表失败", e)
            ResultVo.error(e.message ?: "获取技能列表失败")
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取技能详情", description = "根据技能 ID 获取技能信息")
    fun getSkill(
        @Parameter(description = "技能 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<SkillResponse?> {
        return try {
            val skill = skillService.getSkill(id)
            // 获取仓库信息
            val repository = skillRepositoryService.getSkillRepository(skill!!.repositoryId)
            ResultVo.success(SkillResponse.fromEntity(skill, repository))
        } catch (e: Exception) {
            log.error("获取技能详情失败", e)
            ResultVo.error(e.message ?: "获取技能详情失败")
        }
    }

    @PostMapping
    @Operation(summary = "创建技能", description = "新增技能信息")
    fun createSkill(
        @Valid @RequestBody request: SkillCreateRequest
    ): ResultVo<Void> {
        return try {
            if (skillService.createSkill(request)) ResultVo.success() else ResultVo.error("创建技能失败")
        } catch (e: Exception) {
            log.error("创建技能失败", e)
            ResultVo.error(e.message ?: "创建技能失败")
        }
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "更新技能", description = "根据技能 ID 更新技能信息")
    fun updateSkill(
        @Parameter(description = "技能 ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: SkillUpdateRequest
    ): ResultVo<Void> {
        return try {
            if (skillService.updateSkill(id, request)) ResultVo.success() else ResultVo.error("更新技能失败")
        } catch (e: Exception) {
            log.error("更新技能失败", e)
            ResultVo.error(e.message ?: "更新技能失败")
        }
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换技能状态", description = "根据技能 ID 切换技能状态")
    fun toggleSkill(
        @Parameter(description = "技能 ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "技能状态") @RequestParam(name = "status") status: Int
    ): ResultVo<Void> {
        return try {
            if (skillService.toggleSkillStatus(id, status)) ResultVo.success() else ResultVo.error("更新技能失败")
        } catch (e: Exception) {
            log.error("更新技能失败", e)
            ResultVo.error(e.message ?: "更新技能失败")
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除技能", description = "根据技能 ID 删除技能")
    fun deleteSkill(
        @Parameter(description = "技能 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<Void> {
        return try {
            if (skillService.deleteSkill(id)) ResultVo.success() else ResultVo.error("删除技能失败")
        } catch (e: Exception) {
            log.error("删除技能失败", e)
            ResultVo.error(e.message ?: "删除技能失败")
        }
    }

    @PostMapping("/batch")
    @Operation(summary = "批量保存技能", description = "批量保存技能到指定仓库，重名技能会被覆盖")
    fun batchSaveSkills(
        @Parameter(description = "仓库 ID") @RequestParam(name = "repositoryId") repositoryId: Long,
        @Parameter(description = "技能列表") @RequestBody skills: List<String>
    ): ResultVo<Int> {
        return try {
            val count = skillService.batchSaveSkills(repositoryId, skills)
            ResultVo.success(count)
        } catch (e: Exception) {
            log.error("批量保存技能失败", e)
            ResultVo.error(e.message ?: "批量保存技能失败")
        }
    }
}
