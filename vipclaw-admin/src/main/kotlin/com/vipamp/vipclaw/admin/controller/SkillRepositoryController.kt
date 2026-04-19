package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.admin.dto.*
import com.vipamp.vipclaw.admin.entity.SkillRepository
import com.vipamp.vipclaw.admin.service.SkillRepositoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import kotlin.collections.map

/**
 * 技能仓库管理控制器
 *
 * @author vipamp
 * @since 2026-03-16
 */
@RestController
@RequestMapping("/admin/skill-repositories")
@Tag(name = "技能仓库管理", description = "技能仓库相关接口")
class SkillRepositoryController(
    private val skillRepositoryService: SkillRepositoryService
) {

    private val log = LoggerFactory.getLogger(SkillRepositoryController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取技能仓库列表", description = "分页查询技能仓库信息")
    fun getRepositoryPage(
        @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
        @Parameter(description = "仓库名称") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) status: Int?
    ): ResultVo<Page<SkillRepositoryResponse>> {
        return try {
            val page = skillRepositoryService.getRepositoryPage(name, status, pageNum ?: 1, pageSize ?: 10)
            val responsePage = convertToResponsePage(page)
            ResultVo.success(responsePage)
        } catch (e: Exception) {
            log.error("获取技能仓库列表失败", e)
            ResultVo.error(e.message ?: "获取技能仓库列表失败")
        }
    }

    @GetMapping("/active")
    @Operation(summary = "获取所有启用的仓库列表", description = "获取所有启用的仓库列表")
    fun getActiveRepositories(): ResultVo<List<SkillRepositoryResponse>> {
        return try {
            val repositories = skillRepositoryService.getActiveRepositories()
            val responseList = repositories.map { SkillRepositoryResponse.fromEntity(it) }
            ResultVo.success(responseList)
        } catch (e: Exception) {
            log.error("获取启用的仓库列表失败", e)
            ResultVo.error(e.message ?: "获取启用的仓库列表失败")
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取技能仓库详情", description = "根据技能仓库 ID 获取技能仓库信息")
    fun getRepositoryById(
        @Parameter(description = "技能仓库 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<SkillRepositoryResponse> {
        return try {
            val repository = skillRepositoryService.getRepositoryById(id)
            ResultVo.success(SkillRepositoryResponse.fromEntity(repository))
        } catch (e: Exception) {
            log.error("获取技能仓库详情失败", e)
            ResultVo.error(e.message ?: "获取技能仓库详情失败")
        }
    }

    @PostMapping
    @Operation(summary = "创建技能仓库", description = "新增技能仓库信息")
    fun createRepository(
        @Valid @RequestBody request: SkillRepositoryCreateRequest
    ): ResultVo<Void> {
        return try {
            if (skillRepositoryService.createRepository(request)) ResultVo.success() else ResultVo.error("创建技能仓库失败")
        } catch (e: Exception) {
            log.error("创建技能仓库失败", e)
            ResultVo.error(e.message ?: "创建技能仓库失败")
        }
    }

    @PutMapping("/update/{repositoryId}")
    @Operation(summary = "更新技能仓库", description = "根据技能仓库 ID 更新技能仓库信息")
    fun updateRepository(
        @Parameter(description = "技能仓库 ID") @PathVariable(name = "repositoryId") repositoryId: Long,
        @Valid @RequestBody request: SkillRepositoryUpdateRequest
    ): ResultVo<Void> {
        return try {
            if (skillRepositoryService.updateRepository(repositoryId, request)) ResultVo.success() else ResultVo.error("更新技能仓库失败")
        } catch (e: Exception) {
            log.error("更新技能仓库失败", e)
            ResultVo.error(e.message ?: "更新技能仓库失败")
        }
    }

    @PutMapping("/toggle/{repositoryId}")
    @Operation(summary = "切换技能仓库状态", description = "根据技能仓库 ID 切换技能仓库状态")
    fun toggleRepository(
        @Parameter(description = "技能仓库 ID") @PathVariable(name = "repositoryId") repositoryId: Long,
        @Parameter(description = "技能仓库状态") @RequestParam(name = "status") status: Int
    ): ResultVo<Void> {
        return try {
            if (skillRepositoryService.toggleRepositoryStatus(repositoryId, status)) ResultVo.success() else ResultVo.error("更新技能仓库失败")
        } catch (e: Exception) {
            log.error("更新技能仓库失败", e)
            ResultVo.error(e.message ?: "更新技能仓库失败")
        }
    }

    @DeleteMapping("/{repositoryId}")
    @Operation(summary = "删除技能仓库", description = "根据技能仓库 ID 删除技能仓库")
    fun deleteRepository(
        @Parameter(description = "技能仓库 ID") @PathVariable(name = "repositoryId") repositoryId: Long
    ): ResultVo<Void> {
        return try {
            if (skillRepositoryService.deleteRepository(repositoryId)) ResultVo.success() else ResultVo.error("删除技能仓库失败")
        } catch (e: Exception) {
            log.error("删除技能仓库失败", e)
            ResultVo.error(e.message ?: "删除技能仓库失败")
        }
    }

    @GetMapping("/fetch/{repositoryId}")
    @Operation(summary = "获取远程技能列表", description = "从远程仓库获取可同步的技能列表")
    fun fetchRemoteSkills(
        @Parameter(description = "技能仓库 ID") @PathVariable(name = "repositoryId") repositoryId: Long
    ): ResultVo<List<SyncSkillResponse>> {
        return try {
            val skills = skillRepositoryService.fetchRemoteSkills(repositoryId)
            ResultVo.success(skills)
        } catch (e: Exception) {
            log.error("获取远程技能列表失败", e)
            ResultVo.error(e.message ?: "获取远程技能列表失败")
        }
    }

    /**
     * 分页结果转换
     */
    private fun convertToResponsePage(page: Page<SkillRepository>): Page<SkillRepositoryResponse> {
        val responsePage = Page<SkillRepositoryResponse>(page.current, page.size)
        responsePage.total = page.total
        responsePage.size = page.size
        responsePage.current = page.current
        responsePage.pages = page.pages
        responsePage.records = page.records.map { SkillRepositoryResponse.fromEntity(it) }
        return responsePage
    }
}
