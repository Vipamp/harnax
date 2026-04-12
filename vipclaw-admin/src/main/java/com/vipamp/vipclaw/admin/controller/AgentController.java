package com.vipamp.vipclaw.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vipamp.vipclaw.admin.dto.AgentCreateRequest;
import com.vipamp.vipclaw.admin.dto.AgentResponse;
import com.vipamp.vipclaw.admin.dto.AgentUpdateRequest;
import com.vipamp.vipclaw.admin.entity.Agent;
import com.vipamp.vipclaw.admin.service.AgentService;
import com.vipamp.vipclaw.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 智能体管理控制器
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Slf4j
@RestController
@RequestMapping("/agents")
@RequiredArgsConstructor
@Tag(name = "智能体管理", description = "智能体相关接口")
public class AgentController {

    private final AgentService agentService;

    @GetMapping("/page")
    @Operation(summary = "分页获取智能体列表", description = "分页查询智能体信息")
    public Result<Page<AgentResponse>> getAgentPage(
            @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "智能体名称") @RequestParam(name = "name", required = false) String name,
            @Parameter(description = "状态筛选字段") @RequestParam(name = "status", required = false) Integer status) {
        try {
            Page<Agent> page = agentService.getAgentPage(name, status, pageNum, pageSize);
            // 使用新的转换方法，包含完整的技能和 MCP 信息
            Page<AgentResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
            responsePage.setTotal(page.getTotal());
            responsePage.setSize(page.getSize());
            responsePage.setCurrent(page.getCurrent());
            responsePage.setPages(page.getPages());
            responsePage.setRecords(page.getRecords().stream()
                    .map(agentService::convertToResponse)
                    .toList());
            return Result.success(responsePage);
        } catch (Exception e) {
            log.error("获取智能体列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取智能体详情", description = "根据智能体 ID 获取智能体信息")
    public Result<AgentResponse> getAgentById(
            @Parameter(description = "智能体 ID") @PathVariable(name = "id") Long id) {
        try {
            Agent agent = agentService.getAgentById(id);
            // 使用新的转换方法，包含完整的技能和 MCP 信息
            return Result.success(agentService.convertToResponse(agent));
        } catch (Exception e) {
            log.error("获取智能体详情失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "创建智能体", description = "新增智能体信息")
    public Result<Void> createAgent(
            @Validated @RequestBody AgentCreateRequest request) {
        try {
            return agentService.createAgent(request) ? Result.success() : Result.error("创建智能体失败");
        } catch (Exception e) {
            log.error("创建智能体失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/update/{agentId}")
    @Operation(summary = "更新智能体", description = "根据智能体 ID 更新智能体信息")
    public Result<Void> updateAgent(
            @Parameter(description = "智能体 ID") @PathVariable(name = "agentId") Long agentId,
            @Validated @RequestBody AgentUpdateRequest request) {
        try {
            request.setId(agentId);
            return agentService.updateAgent(agentId, request) ? Result.success() : Result.error("更新智能体失败");
        } catch (Exception e) {
            log.error("更新智能体失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/toggle/{agentId}")
    @Operation(summary = "切换智能体状态", description = "根据智能体 ID 切换智能体状态")
    public Result<Void> toggleAgent(
            @Parameter(description = "智能体 ID") @PathVariable(name = "agentId") Long agentId,
            @Parameter(description = "智能体状态") @RequestParam(name = "status") Integer status) {
        try {
            return agentService.toggleAgentStatus(agentId, status) ? Result.success() : Result.error("更新智能体失败");
        } catch (Exception e) {
            log.error("更新智能体失败", e);
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{agentId}")
    @Operation(summary = "删除智能体", description = "根据智能体 ID 删除智能体")
    public Result<Void> deleteAgent(
            @Parameter(description = "智能体 ID") @PathVariable(name = "agentId") Long agentId) {
        try {
            return agentService.deleteAgent(agentId) ? Result.success() : Result.error("删除智能体失败");
        } catch (Exception e) {
            log.error("删除智能体失败", e);
            return Result.error(e.getMessage());
        }
    }
}
