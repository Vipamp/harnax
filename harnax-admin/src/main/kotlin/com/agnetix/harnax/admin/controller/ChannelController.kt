package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.ChannelCreateRequest
import com.agnetix.harnax.admin.dto.ChannelResponse
import com.agnetix.harnax.admin.dto.ChannelUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.service.ChannelService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

/**
 * Channel management controller
 */
@RestController
@RequestMapping("/api/channels")
@Tag(name = "Channel Management", description = "Channel related APIs")
class ChannelController(
    private val channelService: ChannelService,
) {

    private val log = LoggerFactory.getLogger(ChannelController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get channel list with pagination", description = "Paginated query for channel information")
    fun pageChannel(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "Search keyword") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "Type filter") @RequestParam(name = "type", required = false) type: String?,
        @Parameter(description = "Status filter") @RequestParam(name = "status", required = false) status: Int?,
    ): ResultVo<Page<ChannelResponse>> = try {
        val page = channelService.page(keyword, type, status, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { channelService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get channel list", e)
        ResultVo.error(e.message ?: "Failed to get channel list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get channel details", description = "Get channel information by channel ID")
    fun getChannel(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<ChannelResponse?> = try {
        val channel = channelService.getChannel(id)
        ResultVo.success(channel?.let { channelService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get channel details", e)
        ResultVo.error(e.message ?: "Failed to get channel details")
    }

    @PostMapping
    @Operation(summary = "Create channel", description = "Add new channel information")
    fun createChannel(
        @Validated @RequestBody request: ChannelCreateRequest,
    ): ResultVo<Void> = try {
        if (channelService.createChannel(request)) ResultVo.success() else ResultVo.error("Failed to create channel")
    } catch (e: Exception) {
        log.error("Failed to create channel", e)
        ResultVo.error(e.message ?: "Failed to create channel")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update channel", description = "Update channel information by channel ID")
    fun updateChannel(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long,
        @Validated @RequestBody request: ChannelUpdateRequest,
    ): ResultVo<Void> = try {
        if (channelService.updateChannel(id, request)) ResultVo.success() else ResultVo.error("Failed to update channel")
    } catch (e: Exception) {
        log.error("Failed to update channel", e)
        ResultVo.error(e.message ?: "Failed to update channel")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle channel status", description = "Toggle channel status by channel ID")
    fun toggleChannel(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Status") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (channelService.toggleChannelStatus(id, status)) {
            ResultVo.success()
        } else {
            ResultVo.error("Failed to update channel")
        }
    } catch (e: Exception) {
        log.error("Failed to update channel", e)
        ResultVo.error(e.message ?: "Failed to update channel")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete channel", description = "Delete channel by channel ID")
    fun deleteChannel(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (channelService.deleteChannel(id)) {
            ResultVo.success()
        } else {
            ResultVo.error("Failed to delete channel")
        }
    } catch (e: Exception) {
        log.error("Failed to delete channel", e)
        ResultVo.error(e.message ?: "Failed to delete channel")
    }
}
