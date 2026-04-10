package com.vipamp.vipclaw.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vipamp.vipclaw.admin.dto.SessionCreateRequest;
import com.vipamp.vipclaw.admin.dto.SessionResponse;
import com.vipamp.vipclaw.admin.entity.Session;
import com.vipamp.vipclaw.admin.service.SessionService;
import com.vipamp.vipclaw.admin.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 会话管理控制器
 *
 * @author vipamp
 * @since 2026-03-25
 */
@Slf4j
@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
@Tag(name = "会话管理", description = "会话相关接口")
public class SessionController {

    private final SessionService sessionService;

    @GetMapping("/page")
    @Operation(summary = "分页获取会话列表", description = "分页查询会话信息")
    public Result<Page<SessionResponse>> getSessionPage(
            @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "会话名称") @RequestParam(name = "keyword", required = false) String keyword,
            @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) Integer status) {
        try {
            Page<Session> page = sessionService.getSessionPage(keyword, status, pageNum, pageSize);
            Page<SessionResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
            responsePage.setTotal(page.getTotal());
            responsePage.setSize(page.getSize());
            responsePage.setCurrent(page.getCurrent());
            responsePage.setPages(page.getPages());
            responsePage.setRecords(page.getRecords().stream()
                    .map(sessionService::convertToResponse)
                    .toList());
            return Result.success(responsePage);
        } catch (Exception e) {
            log.error("获取会话列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取会话详情", description = "根据会话 ID 获取会话信息")
    public Result<SessionResponse> getSessionById(
            @Parameter(description = "会话 ID") @PathVariable(name = "id") Long id) {
        try {
            Session session = sessionService.getSessionById(id);
            return Result.success(sessionService.convertToResponse(session));
        } catch (Exception e) {
            log.error("获取会话详情失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/check-title")
    @Operation(summary = "检查会话名称是否存在", description = "检查会话名称是否已存在")
    public Result<Boolean> checkSessionTitle(
            @Parameter(description = "会话名称") @RequestParam(name = "title") String title) {
        try {
            boolean exists = sessionService.existsByTitle(title);
            return Result.success(exists);
        } catch (Exception e) {
            log.error("检查会话名称失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "创建会话", description = "新增会话信息")
    public Result<Void> createSession(
            @Validated @RequestBody SessionCreateRequest request) {
        try {
            return sessionService.createSession(request) ? Result.success() : Result.error("创建会话失败");
        } catch (Exception e) {
            log.error("创建会话失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/toggle/{sessionId}")
    @Operation(summary = "切换会话状态", description = "根据会话 ID 切换会话状态")
    public Result<Void> toggleSession(
            @Parameter(description = "会话 ID") @PathVariable(name = "sessionId") Long sessionId,
            @Parameter(description = "会话状态") @RequestParam(name = "status") Integer status) {
        try {
            return sessionService.toggleSessionStatus(sessionId, status) ? Result.success() : Result.error("更新会话失败");
        } catch (Exception e) {
            log.error("更新会话失败", e);
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "删除会话", description = "根据会话 ID 删除会话")
    public Result<Void> deleteSession(
            @Parameter(description = "会话 ID") @PathVariable(name = "sessionId") Long sessionId) {
        try {
            return sessionService.deleteSession(sessionId) ? Result.success() : Result.error("删除会话失败");
        } catch (Exception e) {
            log.error("删除会话失败", e);
            return Result.error(e.getMessage());
        }
    }
}