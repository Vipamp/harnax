package com.vipamp.vipclaw.admin.controller

import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.vipamp.vipclaw.admin.dto.ChannelCreateRequest
import com.vipamp.vipclaw.admin.dto.ChannelResponse
import com.vipamp.vipclaw.admin.dto.ChannelUpdateRequest
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.service.ChannelService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

/**
 * Channel 管理控制器
 *
 * @author vipamp
 * @since 2026-04-08
 */
@RestController
@RequestMapping("/channels")
@Tag(name = "Channel 管理", description = "Channel 通道相关接口")
class ChannelController(
    private val channelService: ChannelService
) {

    private val log = LoggerFactory.getLogger(ChannelController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取 Channel 列表", description = "分页查询 Channel 信息")
    fun getChannelPage(
        @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
        @Parameter(description = "搜索关键字") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "类型筛选") @RequestParam(name = "type", required = false) type: String?,
        @Parameter(description = "状态筛选") @RequestParam(name = "status", required = false) status: Int?
    ): ResultVo<Page<ChannelResponse>> {
        return try {
            val page = channelService.getChannelPage(keyword, type, status, pageNum ?: 1, pageSize ?: 10)
            val responsePage = Page<ChannelResponse>(page.current, page.size)
            responsePage.total = page.total
            responsePage.size = page.size
            responsePage.current = page.current
            responsePage.pages = page.pages
            responsePage.records = page.records.map { channelService.convertToResponse(it) }
            ResultVo.success(responsePage)
        } catch (e: Exception) {
            log.error("获取 Channel 列表失败", e)
            ResultVo.error(e.message ?: "获取 Channel 列表失败")
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取 Channel 详情", description = "根据 Channel ID 获取 Channel 信息")
    fun getChannelById(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long
    ): ResultVo<ChannelResponse> {
        return try {
            val channel = channelService.getChannelById(id)
            ResultVo.success(channelService.convertToResponse(channel))
        } catch (e: Exception) {
            log.error("获取 Channel 详情失败", e)
            ResultVo.error(e.message ?: "获取 Channel 详情失败")
        }
    }

    @PostMapping
    @Operation(summary = "创建 Channel", description = "新增 Channel 信息")
    fun createChannel(
        @Validated @RequestBody request: ChannelCreateRequest
    ): ResultVo<Void> {
        return try {
            if (channelService.createChannel(request)) ResultVo.success() else ResultVo.error("创建 Channel 失败")
        } catch (e: Exception) {
            log.error("创建 Channel 失败", e)
            ResultVo.error(e.message ?: "创建 Channel 失败")
        }
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "更新 Channel", description = "根据 Channel ID 更新 Channel 信息")
    fun updateChannel(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long,
        @Validated @RequestBody request: ChannelUpdateRequest
    ): ResultVo<Void> {
        return try {
            if (channelService.updateChannel(id, request)) ResultVo.success() else ResultVo.error("更新 Channel 失败")
        } catch (e: Exception) {
            log.error("更新 Channel 失败", e)
            ResultVo.error(e.message ?: "更新 Channel 失败")
        }
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换 Channel 状态", description = "根据 Channel ID 切换 Channel 状态")
    fun toggleChannel(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "状态") @RequestParam(name = "status") status: Int
    ): ResultVo<Void> {
        return try {
            if (channelService.toggleChannelStatus(id, status)) ResultVo.success() else ResultVo.error("更新 Channel 失败")
        } catch (e: Exception) {
            log.error("更新 Channel 失败", e)
            ResultVo.error(e.message ?: "更新 Channel 失败")
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除 Channel", description = "根据 Channel ID 删除 Channel")
    fun deleteChannel(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long
    ): ResultVo<Void> {
        return try {
            if (channelService.deleteChannel(id)) ResultVo.success() else ResultVo.error("删除 Channel 失败")
        } catch (e: Exception) {
            log.error("删除 Channel 失败", e)
            ResultVo.error(e.message ?: "删除 Channel 失败")
        }
    }
}
