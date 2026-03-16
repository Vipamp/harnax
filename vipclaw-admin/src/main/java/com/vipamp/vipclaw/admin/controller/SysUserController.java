package com.vipamp.vipclaw.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest;
import com.vipamp.vipclaw.admin.dto.SysUserResponse;
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest;
import com.vipamp.vipclaw.admin.entity.SysUser;
import com.vipamp.vipclaw.admin.service.SysUserService;
import com.vipamp.vipclaw.admin.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 用户管理控制器
 *
 * @author vipamp
 * @since 2026-03-06
 */
@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "用户管理", description = "用户相关接口")
public class SysUserController {

    private final SysUserService sysUserService;

    @GetMapping("/list")
    @Operation(summary = "分页获取用户列表", description = "分页查询用户信息")
    public Result<Page<SysUserResponse>> getUserPage(
            @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "模糊查询字段") @RequestParam(name = "keyword", required = false) String keyword,
            @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) Integer status) {
        try {
            Page<SysUser> page = sysUserService.getUserPage(keyword, status, pageNum, pageSize);
            Page<SysUserResponse> responsePage = convertToResponsePage(page);
            return Result.success(responsePage);
        } catch (Exception e) {
            log.error("获取用户列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取用户详情", description = "根据用户 ID 获取用户信息")
    public Result<SysUserResponse> getUserById(
            @Parameter(description = "用户 ID") @PathVariable Long id) {
        try {
            SysUser user = sysUserService.getUserById(id);
            return Result.success(SysUserResponse.fromEntity(user));
        } catch (Exception e) {
            log.error("获取用户详情失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "创建用户", description = "新增用户信息")
    public Result<Void> createUser(
            @Valid @RequestBody SysUserCreateRequest request) {
        try {
            return sysUserService.createUser(request) ? Result.success() : Result.error("创建用户失败");
        } catch (Exception e) {
            log.error("创建用户失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/update/{userId}")
    @Operation(summary = "更新用户", description = "根据用户 ID 更新用户信息")
    public Result<Void> updateUser(
            @Parameter(description = "用户 ID") @PathVariable Long userId,
            @Valid @RequestBody SysUserUpdateRequest request) {
        try {
            request.setId(userId);
            return sysUserService.updateUser(userId, request) ? Result.success() : Result.error("更新用户失败");
        } catch (Exception e) {
            log.error("更新用户失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/toggle/{userId}")
    @Operation(summary = "更新用户", description = "根据用户 ID 更新用户信息")
    public Result<Void> toggleUser(
            @Parameter(description = "用户 ID") @PathVariable Long userId,
            @Parameter(description = "用户状态") @RequestParam(name = "status") Integer status) {
        try {
            return sysUserService.toggleUserStatus(userId, status) ? Result.success() : Result.error("更新用户失败");
        } catch (Exception e) {
            log.error("更新用户失败", e);
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "删除用户", description = "根据用户 ID 删除用户")
    public Result<Void> deleteUser(
            @Parameter(description = "用户 ID") @PathVariable Long userId) {
        try {
            return sysUserService.deleteUser(userId) ? Result.success() : Result.error("删除用户失败");
        } catch (Exception e) {
            log.error("删除用户失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 分页结果转换
     */
    private Page<SysUserResponse> convertToResponsePage(Page<SysUser> page) {
        Page<SysUserResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
        responsePage.setTotal(page.getTotal());
        responsePage.setSize(page.getSize());
        responsePage.setCurrent(page.getCurrent());
        responsePage.setPages(page.getPages());
        responsePage.setRecords(page.getRecords().stream()
                .map(SysUserResponse::fromEntity)
                .toList());
        return responsePage;
    }
}
