package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.admin.dto.AgentCreateRequest
import com.vipamp.vipclaw.admin.dto.AgentResponse
import com.vipamp.vipclaw.admin.dto.AgentUpdateRequest
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.service.AgentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

/**
 * 智能体管理控制器
 *
 * @author vipamp
 * @since 2026-03-18
 */
@RestController
@RequestMapping("/admin/agents")
@Tag(name = "智能体管理", description = "智能体相关接口")
class AgentController(
    private val agentService: AgentService
) {

    private val log = LoggerFactory.getLogger(AgentController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取智能体列表", description = "分页查询智能体信息")
    fun getAgentPage(
        @Parameter(description = "页码", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1"
        ) pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10"
        ) pageSize: Int?,
        @Parameter(description = "智能体名称") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) status: Int?
    ): ResultVo<Page<AgentResponse>> {
        return try {
            val page = agentService.getAgentPage(name, status, pageNum ?: 1, pageSize ?: 10)
            // 使用新的转换方法，包含完整的技能和 MCP 信息
            val responsePage = Page<AgentResponse>(page.current, page.size)
            responsePage.total = page.total
            responsePage.size = page.size
            responsePage.current = page.current
            responsePage.pages = page.pages
            responsePage.records = page.records.map { agentService.convertToResponse(it)!! }
            ResultVo.success(responsePage)
        } catch (e: Exception) {
            log.error("获取智能体列表失败", e)
            ResultVo.error(e.message ?: "获取智能体列表失败")
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取智能体详情", description = "根据智能体 ID 获取智能体信息")
    fun getAgentById(
        @Parameter(description = "智能体 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<AgentResponse?> {
        return try {
            val agent = agentService.getAgentById(id)
            // 使用新的转换方法，包含完整的技能和 MCP 信息
            ResultVo.success(agentService.convertToResponse(agent))
        } catch (e: Exception) {
            log.error("获取智能体详情失败", e)
            ResultVo.error(e.message ?: "获取智能体详情失败")
        }
    }

    @PostMapping
    @Operation(summary = "创建智能体", description = "新增智能体信息")
    fun createAgent(
        @Validated @RequestBody request: AgentCreateRequest
    ): ResultVo<Void> {
        return try {
            if (agentService.createAgent(request)) ResultVo.success() else ResultVo.error("创建智能体失败")
        } catch (e: Exception) {
            log.error("创建智能体失败", e)
            ResultVo.error(e.message ?: "创建智能体失败")
        }
    }

    @PutMapping("/update/{agentId}")
    @Operation(summary = "更新智能体", description = "根据智能体 ID 更新智能体信息")
    fun updateAgent(
        @Parameter(description = "智能体 ID") @PathVariable(name = "agentId") agentId: Long,
        @Validated @RequestBody request: AgentUpdateRequest
    ): ResultVo<Void> {
        return try {
            if (agentService.updateAgent(agentId, request)) ResultVo.success() else ResultVo.error("更新智能体失败")
        } catch (e: Exception) {
            log.error("更新智能体失败", e)
            ResultVo.error(e.message ?: "更新智能体失败")
        }
    }

    @PutMapping("/toggle/{agentId}")
    @Operation(summary = "切换智能体状态", description = "根据智能体 ID 切换智能体状态")
    fun toggleAgent(
        @Parameter(description = "智能体 ID") @PathVariable(name = "agentId") agentId: Long,
        @Parameter(description = "智能体状态") @RequestParam(name = "status") status: Int
    ): ResultVo<Void> {
        return try {
            if (agentService.toggleAgentStatus(
                    agentId,
                    status
                )
            ) ResultVo.success() else ResultVo.error("更新智能体失败")
        } catch (e: Exception) {
            log.error("更新智能体失败", e)
            ResultVo.error(e.message ?: "更新智能体失败")
        }
    }

    @DeleteMapping("/{agentId}")
    @Operation(summary = "删除智能体", description = "根据智能体 ID 删除智能体")
    fun deleteAgent(
        @Parameter(description = "智能体 ID") @PathVariable(name = "agentId") agentId: Long
    ): ResultVo<Void> {
        return try {
            if (agentService.deleteAgent(agentId)) ResultVo.success() else ResultVo.error("删除智能体失败")
        } catch (e: Exception) {
            log.error("删除智能体失败", e)
            ResultVo.error(e.message ?: "删除智能体失败")
        }
    }
}
