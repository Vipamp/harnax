package com.vipamp.vipclaw.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vipamp.vipclaw.admin.dto.SkillCreateRequest;
import com.vipamp.vipclaw.admin.dto.SkillResponse;
import com.vipamp.vipclaw.admin.dto.SkillUpdateRequest;
import com.vipamp.vipclaw.admin.entity.Skill;
import com.vipamp.vipclaw.admin.entity.SkillRepository;
import com.vipamp.vipclaw.admin.service.SkillRepositoryService;
import com.vipamp.vipclaw.admin.service.SkillService;
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
 * 技能管理控制器
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Slf4j
@RestController
@RequestMapping("/skills")
@RequiredArgsConstructor
@Tag(name = "技能管理", description = "技能相关接口")
public class SkillController {

    private final SkillService skillService;
    private final SkillRepositoryService skillRepositoryService;

    @GetMapping("/page")
    @Operation(summary = "分页获取技能列表", description = "分页查询技能信息")
    public Result<Page<SkillResponse>> getSkillPage(
            @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "技能名称") @RequestParam(name = "name", required = false) String name,
            @Parameter(description = "仓库ID") @RequestParam(name = "repositoryId", required = false) Long repositoryId,
            @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) Integer status) {
        try {
            Page<Skill> page = skillService.getSkillPage(name, repositoryId, status, pageNum, pageSize);
            Page<SkillResponse> responsePage = convertToResponsePage(page);
            return Result.success(responsePage);
        } catch (Exception e) {
            log.error("获取技能列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取技能详情", description = "根据技能 ID 获取技能信息")
    public Result<SkillResponse> getSkillById(
            @Parameter(description = "技能 ID") @PathVariable(name = "id") Long id) {
        try {
            Skill skill = skillService.getSkillById(id);
            return Result.success(SkillResponse.fromEntity(skill));
        } catch (Exception e) {
            log.error("获取技能详情失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "创建技能", description = "新增技能信息")
    public Result<Void> createSkill(
            @Valid @RequestBody SkillCreateRequest request) {
        try {
            return skillService.createSkill(request) ? Result.success() : Result.error("创建技能失败");
        } catch (Exception e) {
            log.error("创建技能失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/update/{skillId}")
    @Operation(summary = "更新技能", description = "根据技能 ID 更新技能信息")
    public Result<Void> updateSkill(
            @Parameter(description = "技能 ID") @PathVariable(name = "skillId") Long skillId,
            @Valid @RequestBody SkillUpdateRequest request) {
        try {
            request.setId(skillId);
            return skillService.updateSkill(skillId, request) ? Result.success() : Result.error("更新技能失败");
        } catch (Exception e) {
            log.error("更新技能失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/toggle/{skillId}")
    @Operation(summary = "切换技能状态", description = "根据技能 ID 切换技能状态")
    public Result<Void> toggleSkill(
            @Parameter(description = "技能 ID") @PathVariable(name = "skillId") Long skillId,
            @Parameter(description = "技能状态") @RequestParam(name = "status") Integer status) {
        try {
            return skillService.toggleSkillStatus(skillId, status) ? Result.success() : Result.error("更新技能失败");
        } catch (Exception e) {
            log.error("更新技能失败", e);
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{skillId}")
    @Operation(summary = "删除技能", description = "根据技能 ID 删除技能")
    public Result<Void> deleteSkill(
            @Parameter(description = "技能 ID") @PathVariable(name = "skillId") Long skillId) {
        try {
            return skillService.deleteSkill(skillId) ? Result.success() : Result.error("删除技能失败");
        } catch (Exception e) {
            log.error("删除技能失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/batch")
    @Operation(summary = "批量保存技能", description = "批量保存技能到指定仓库，重名技能会被覆盖")
    public Result<Integer> batchSaveSkills(
            @Parameter(description = "仓库 ID") @RequestParam(name = "repositoryId") Long repositoryId,
            @Parameter(description = "技能列表") @RequestBody List<SkillResponse> skills) {
        try {
            Integer count = skillService.batchSaveSkills(repositoryId, skills);
            return Result.success(count);
        } catch (Exception e) {
            log.error("批量保存技能失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 分页结果转换
     */
    private Page<SkillResponse> convertToResponsePage(Page<Skill> page) {
        Page<SkillResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
        responsePage.setTotal(page.getTotal());
        responsePage.setSize(page.getSize());
        responsePage.setCurrent(page.getCurrent());
        responsePage.setPages(page.getPages());
        responsePage.setRecords(page.getRecords().stream()
                .map(skill -> {
                    SkillResponse response = SkillResponse.fromEntity(skill);
                    // 设置仓库名称
                    if (skill.getRepositoryId() != null) {
                        try {
                            SkillRepository repository = skillRepositoryService.getById(skill.getRepositoryId());
                            if (repository != null) {
                                response.setRepositoryName(repository.getName());
                            }
                        } catch (Exception e) {
                            log.warn("获取仓库名称失败，repositoryId: {}", skill.getRepositoryId());
                        }
                    }
                    return response;
                })
                .toList());
        return responsePage;
    }
}
