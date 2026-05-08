package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.config.RequiresEdition
import com.vipamp.vipclaw.admin.dto.*
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.mapRecords
import com.vipamp.vipclaw.admin.service.SkillRepositoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * 技能仓库管理控制器
 * 仅公有云版可用(技能市场功能)
 *
 * @author vipamp
 * @since 2026-03-16
 */
@RestController
@RequestMapping("/api/skill-repositories")
@Tag(name = "技能仓库管理", description = "技能仓库相关接口")
@RequiresEdition("public")
class SkillRepositoryController(
    private val skillRepositoryService: SkillRepositoryService,
) {

    private val log = LoggerFactory.getLogger(SkillRepositoryController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取技能仓库列表", description = "分页查询技能仓库信息")
    fun pageSkillRepository(
        @Parameter(description = "页码", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "仓库名称") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) status: Int?,
    ): ResultVo<Page<SkillRepositoryResponse>> = try {
        val page = skillRepositoryService.page(
            name,
            status,
            pageNum ?: 1,
            pageSize ?: 10,
        )
        ResultVo.success(page.mapRecords { SkillRepositoryResponse.fromEntity(it) })
    } catch (e: Exception) {
        log.error("获取技能仓库列表失败", e)
        ResultVo.error(e.message ?: "获取技能仓库列表失败")
    }

    @GetMapping("/active")
    @Operation(summary = "获取所有启用的仓库列表", description = "获取所有启用的仓库列表")
    fun getActiveRepositories(): ResultVo<List<SkillRepositoryResponse>> = try {
        val repositories = skillRepositoryService.getActiveRepositories()
        val responseList = repositories.map { SkillRepositoryResponse.fromEntity(it) }
        ResultVo.success(responseList)
    } catch (e: Exception) {
        log.error("获取启用的仓库列表失败", e)
        ResultVo.error(e.message ?: "获取启用的仓库列表失败")
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取技能仓库详情", description = "根据技能仓库 ID 获取技能仓库信息")
    fun getSkillRepository(
        @Parameter(description = "技能仓库 ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<SkillRepositoryResponse?> = try {
        val repository = skillRepositoryService.getSkillRepository(id)
        ResultVo.success(repository?.let { SkillRepositoryResponse.fromEntity(it) })
    } catch (e: Exception) {
        log.error("获取技能仓库详情失败", e)
        ResultVo.error(e.message ?: "获取技能仓库详情失败")
    }

    @PostMapping
    @Operation(summary = "创建技能仓库", description = "新增技能仓库信息")
    fun createRepository(
        @Valid @RequestBody request: SkillRepositoryCreateRequest,
    ): ResultVo<Void> = try {
        if (skillRepositoryService.createSkillRepository(request)) ResultVo.success() else ResultVo.error("创建技能仓库失败")
    } catch (e: Exception) {
        log.error("创建技能仓库失败", e)
        ResultVo.error(e.message ?: "创建技能仓库失败")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "更新技能仓库", description = "根据技能仓库 ID 更新技能仓库信息")
    fun updateSkillRepository(
        @Parameter(description = "技能仓库 ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: SkillRepositoryUpdateRequest,
    ): ResultVo<Void> = try {
        if (skillRepositoryService.updateSkillRepository(
                id,
                request,
            )
        ) {
            ResultVo.success()
        } else {
            ResultVo.error("更新技能仓库失败")
        }
    } catch (e: Exception) {
        log.error("更新技能仓库失败", e)
        ResultVo.error(e.message ?: "更新技能仓库失败")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换技能仓库状态", description = "根据技能仓库 ID 切换技能仓库状态")
    fun toggleSkillRepository(
        @Parameter(description = "技能仓库 ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "技能仓库状态") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (skillRepositoryService.toggleSkillRepository(
                id,
                status,
            )
        ) {
            ResultVo.success()
        } else {
            ResultVo.error("更新技能仓库失败")
        }
    } catch (e: Exception) {
        log.error("更新技能仓库失败", e)
        ResultVo.error(e.message ?: "更新技能仓库失败")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除技能仓库", description = "根据技能仓库 ID 删除技能仓库")
    fun deleteSkillRepository(
        @Parameter(description = "技能仓库 ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (skillRepositoryService.deleteSkillRepository(id)) ResultVo.success() else ResultVo.error("删除技能仓库失败")
    } catch (e: Exception) {
        log.error("删除技能仓库失败", e)
        ResultVo.error(e.message ?: "删除技能仓库失败")
    }

    @GetMapping("/fetch/{id}")
    @Operation(summary = "获取远程技能列表", description = "从远程仓库获取可同步的技能列表")
    fun fetchRemoteSkills(
        @Parameter(description = "技能仓库 ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<List<SyncSkillResponse>> = try {
        val skills = skillRepositoryService.fetchRemoteSkills(id)
        ResultVo.success(skills)
    } catch (e: Exception) {
        log.error("获取远程技能列表失败", e)
        ResultVo.error(e.message ?: "获取远程技能列表失败")
    }
}
