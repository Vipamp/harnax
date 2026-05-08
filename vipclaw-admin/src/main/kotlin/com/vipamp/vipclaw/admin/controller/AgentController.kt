package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.config.RequiresEdition
import com.vipamp.vipclaw.admin.dto.AgentCreateRequest
import com.vipamp.vipclaw.admin.dto.AgentResponse
import com.vipamp.vipclaw.admin.dto.AgentUpdateRequest
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.mapRecords
import com.vipamp.vipclaw.admin.service.AgentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

/**
 * 智能体管理控制器
 * 企业版和公有云版可用(Agent 共享功能)
 *
 * @author vipamp
 * @since 2026-03-18
 */
@RestController
@RequestMapping("/api/agents")
@Tag(name = "智能体管理", description = "智能体相关接口")
@RequiresEdition("enterprise", "public")
class AgentController(
    private val agentService: AgentService,
) {

    private val log = LoggerFactory.getLogger(AgentController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取智能体列表", description = "分页查询智能体信息")
    fun pageAgent(
        @Parameter(description = "页码", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "智能体名称") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) status: Int?,
    ): ResultVo<Page<AgentResponse>> = try {
        val page = agentService.page(name, status, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { agentService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("获取智能体列表失败", e)
        ResultVo.error(e.message ?: "获取智能体列表失败")
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取智能体详情", description = "根据智能体 ID 获取智能体信息")
    fun getAgent(
        @Parameter(description = "智能体 ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<AgentResponse?> = try {
        ResultVo.success(agentService.getAgent(id)?.let { agentService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("获取智能体详情失败", e)
        ResultVo.error(e.message ?: "获取智能体详情失败")
    }

    @PostMapping
    @Operation(summary = "创建智能体", description = "新增智能体信息")
    fun createAgent(
        @Validated @RequestBody request: AgentCreateRequest,
    ): ResultVo<Void> = try {
        if (agentService.createAgent(request)) ResultVo.success() else ResultVo.error("创建智能体失败")
    } catch (e: Exception) {
        log.error("创建智能体失败", e)
        ResultVo.error(e.message ?: "创建智能体失败")
    }

    @PutMapping("/update/{agentId}")
    @Operation(summary = "更新智能体", description = "根据智能体 ID 更新智能体信息")
    fun updateAgent(
        @Parameter(description = "智能体 ID") @PathVariable(name = "agentId") agentId: Long,
        @Validated @RequestBody request: AgentUpdateRequest,
    ): ResultVo<Void> = try {
        if (agentService.updateAgent(agentId, request)) ResultVo.success() else ResultVo.error("更新智能体失败")
    } catch (e: Exception) {
        log.error("更新智能体失败", e)
        ResultVo.error(e.message ?: "更新智能体失败")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换智能体状态", description = "根据智能体 ID 切换智能体状态")
    fun toggleAgent(
        @Parameter(description = "智能体 ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "智能体状态") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (agentService.toggleAgentStatus(id, status)) ResultVo.success() else ResultVo.error("更新智能体失败")
    } catch (e: Exception) {
        log.error("更新智能体失败", e)
        ResultVo.error(e.message ?: "更新智能体失败")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除智能体", description = "根据智能体 ID 删除智能体")
    fun deleteAgent(
        @Parameter(description = "智能体 ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (agentService.deleteAgent(id)) ResultVo.success() else ResultVo.error("删除智能体失败")
    } catch (e: Exception) {
        log.error("删除智能体失败", e)
        ResultVo.error(e.message ?: "删除智能体失败")
    }
}
