package com.vipamp.vipclaw.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vipamp.vipclaw.admin.dto.ChannelCreateRequest;
import com.vipamp.vipclaw.admin.dto.ChannelResponse;
import com.vipamp.vipclaw.admin.dto.ChannelUpdateRequest;
import com.vipamp.vipclaw.admin.entity.Channel;
import com.vipamp.vipclaw.admin.service.ChannelService;
import com.vipamp.vipclaw.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Channel 管理控制器
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Slf4j
@RestController
@RequestMapping("/channels")
@RequiredArgsConstructor
@Tag(name = "Channel 管理", description = "Channel 通道相关接口")
public class ChannelController {

    private final ChannelService channelService;

    @GetMapping("/page")
    @Operation(summary = "分页获取 Channel 列表", description = "分页查询 Channel 信息")
    public Result<Page<ChannelResponse>> getChannelPage(
            @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "搜索关键字") @RequestParam(name = "keyword", required = false) String keyword,
            @Parameter(description = "类型筛选") @RequestParam(name = "type", required = false) String type,
            @Parameter(description = "状态筛选") @RequestParam(name = "status", required = false) Integer status) {
        try {
            Page<Channel> page = channelService.getChannelPage(keyword, type, status, pageNum, pageSize);
            Page<ChannelResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
            responsePage.setTotal(page.getTotal());
            responsePage.setSize(page.getSize());
            responsePage.setCurrent(page.getCurrent());
            responsePage.setPages(page.getPages());
            responsePage.setRecords(page.getRecords().stream()
                    .map(channelService::convertToResponse)
                    .toList());
            return Result.success(responsePage);
        } catch (Exception e) {
            log.error("获取 Channel 列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取 Channel 详情", description = "根据 Channel ID 获取 Channel 信息")
    public Result<ChannelResponse> getChannelById(
            @Parameter(description = "Channel ID") @PathVariable(name = "id") Long id) {
        try {
            Channel channel = channelService.getChannelById(id);
            return Result.success(channelService.convertToResponse(channel));
        } catch (Exception e) {
            log.error("获取 Channel 详情失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "创建 Channel", description = "新增 Channel 信息")
    public Result<Void> createChannel(
            @Validated @RequestBody ChannelCreateRequest request) {
        try {
            return channelService.createChannel(request) ? Result.success() : Result.error("创建 Channel 失败");
        } catch (Exception e) {
            log.error("创建 Channel 失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "更新 Channel", description = "根据 Channel ID 更新 Channel 信息")
    public Result<Void> updateChannel(
            @Parameter(description = "Channel ID") @PathVariable(name = "id") Long id,
            @Validated @RequestBody ChannelUpdateRequest request) {
        try {
            request.setId(id);
            return channelService.updateChannel(id, request) ? Result.success() : Result.error("更新 Channel 失败");
        } catch (Exception e) {
            log.error("更新 Channel 失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换 Channel 状态", description = "根据 Channel ID 切换 Channel 状态")
    public Result<Void> toggleChannel(
            @Parameter(description = "Channel ID") @PathVariable(name = "id") Long id,
            @Parameter(description = "状态") @RequestParam(name = "status") Integer status) {
        try {
            return channelService.toggleChannelStatus(id, status) ? Result.success() : Result.error("更新 Channel 失败");
        } catch (Exception e) {
            log.error("更新 Channel 失败", e);
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除 Channel", description = "根据 Channel ID 删除 Channel")
    public Result<Void> deleteChannel(
            @Parameter(description = "Channel ID") @PathVariable(name = "id") Long id) {
        try {
            return channelService.deleteChannel(id) ? Result.success() : Result.error("删除 Channel 失败");
        } catch (Exception e) {
            log.error("删除 Channel 失败", e);
            return Result.error(e.getMessage());
        }
    }
}
