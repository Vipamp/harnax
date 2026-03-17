package com.vipamp.vipclaw.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vipamp.vipclaw.admin.dto.SkillRepositoryCreateRequest;
import com.vipamp.vipclaw.admin.dto.SkillRepositoryResponse;
import com.vipamp.vipclaw.admin.dto.SkillRepositoryUpdateRequest;
import com.vipamp.vipclaw.admin.dto.SyncSkillResponse;
import com.vipamp.vipclaw.admin.entity.SkillRepository;
import com.vipamp.vipclaw.admin.service.SkillRepositoryService;
import com.vipamp.vipclaw.admin.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 技能仓库管理控制器
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Slf4j
@RestController
@RequestMapping("/skill-repositories")
@RequiredArgsConstructor
@Tag(name = "技能仓库管理", description = "技能仓库相关接口")
public class SkillRepositoryController {

    private final SkillRepositoryService skillRepositoryService;

    @GetMapping("/page")
    @Operation(summary = "分页获取技能仓库列表", description = "分页查询技能仓库信息")
    public Result<Page<SkillRepositoryResponse>> getRepositoryPage(
            @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "仓库名称") @RequestParam(name = "name", required = false) String name,
            @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) Integer status) {
        try {
            Page<SkillRepository> page = skillRepositoryService.getRepositoryPage(name, status, pageNum, pageSize);
            Page<SkillRepositoryResponse> responsePage = convertToResponsePage(page);
            return Result.success(responsePage);
        } catch (Exception e) {
            log.error("获取技能仓库列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/active")
    @Operation(summary = "获取所有启用的仓库列表", description = "获取所有启用的仓库列表")
    public Result<List<SkillRepositoryResponse>> getActiveRepositories() {
        try {
            List<SkillRepository> repositories = skillRepositoryService.getActiveRepositories();
            List<SkillRepositoryResponse> responseList = repositories.stream()
                    .map(SkillRepositoryResponse::fromEntity)
                    .toList();
            return Result.success(responseList);
        } catch (Exception e) {
            log.error("获取启用的仓库列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取技能仓库详情", description = "根据技能仓库 ID 获取技能仓库信息")
    public Result<SkillRepositoryResponse> getRepositoryById(
            @Parameter(description = "技能仓库 ID") @PathVariable(name = "id") Long id) {
        try {
            SkillRepository repository = skillRepositoryService.getRepositoryById(id);
            return Result.success(SkillRepositoryResponse.fromEntity(repository));
        } catch (Exception e) {
            log.error("获取技能仓库详情失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "创建技能仓库", description = "新增技能仓库信息")
    public Result<Void> createRepository(
            @Valid @RequestBody SkillRepositoryCreateRequest request) {
        try {
            return skillRepositoryService.createRepository(request) ? Result.success() : Result.error("创建技能仓库失败");
        } catch (Exception e) {
            log.error("创建技能仓库失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/update/{repositoryId}")
    @Operation(summary = "更新技能仓库", description = "根据技能仓库 ID 更新技能仓库信息")
    public Result<Void> updateRepository(
            @Parameter(description = "技能仓库 ID") @PathVariable(name = "repositoryId") Long repositoryId,
            @Valid @RequestBody SkillRepositoryUpdateRequest request) {
        try {
            request.setId(repositoryId);
            return skillRepositoryService.updateRepository(repositoryId, request) ? Result.success() : Result.error("更新技能仓库失败");
        } catch (Exception e) {
            log.error("更新技能仓库失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/toggle/{repositoryId}")
    @Operation(summary = "切换技能仓库状态", description = "根据技能仓库 ID 切换技能仓库状态")
    public Result<Void> toggleRepository(
            @Parameter(description = "技能仓库 ID") @PathVariable(name = "repositoryId") Long repositoryId,
            @Parameter(description = "技能仓库状态") @RequestParam(name = "status") Integer status) {
        try {
            return skillRepositoryService.toggleRepositoryStatus(repositoryId, status) ? Result.success() : Result.error("更新技能仓库失败");
        } catch (Exception e) {
            log.error("更新技能仓库失败", e);
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{repositoryId}")
    @Operation(summary = "删除技能仓库", description = "根据技能仓库 ID 删除技能仓库")
    public Result<Void> deleteRepository(
            @Parameter(description = "技能仓库 ID") @PathVariable(name = "repositoryId") Long repositoryId) {
        try {
            return skillRepositoryService.deleteRepository(repositoryId) ? Result.success() : Result.error("删除技能仓库失败");
        } catch (Exception e) {
            log.error("删除技能仓库失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/fetch/{repositoryId}")
    @Operation(summary = "获取远程技能列表", description = "从远程仓库获取可同步的技能列表")
    public Result<List<SyncSkillResponse>> fetchRemoteSkills(
            @Parameter(description = "技能仓库 ID") @PathVariable(name = "repositoryId") Long repositoryId) {
        try {
            List<SyncSkillResponse> skills = skillRepositoryService.fetchRemoteSkills(repositoryId);
            return Result.success(skills);
        } catch (Exception e) {
            log.error("获取远程技能列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 分页结果转换
     */
    private Page<SkillRepositoryResponse> convertToResponsePage(Page<SkillRepository> page) {
        Page<SkillRepositoryResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
        responsePage.setTotal(page.getTotal());
        responsePage.setSize(page.getSize());
        responsePage.setCurrent(page.getCurrent());
        responsePage.setPages(page.getPages());
        responsePage.setRecords(page.getRecords().stream()
                .map(SkillRepositoryResponse::fromEntity)
                .toList());
        return responsePage;
    }
}
