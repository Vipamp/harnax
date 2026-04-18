package com.vipamp.vipclaw.admin.service

import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.IService
import com.vipamp.vipclaw.admin.dto.AgentCreateRequest
import com.vipamp.vipclaw.admin.dto.AgentResponse
import com.vipamp.vipclaw.admin.dto.AgentUpdateRequest
import com.vipamp.vipclaw.admin.entity.Agent

/**
 * 智能体服务接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
interface AgentService : IService<Agent> {

    /**
     * 分页查询智能体列表
     *
     * @param name    智能体名称
     * @param status  状态筛选字段
     * @param current 当前页码
     * @param size    每页大小
     * @return 分页结果
     */
    fun getAgentPage(name: String?, status: Int?, current: Int, size: Int): Page<Agent>

    /**
     * 获取单个智能体详情
     *
     * @param id 智能体 ID
     * @return 智能体实体
     */
    fun getAgentById(id: Long): Agent

    /**
     * 创建智能体
     *
     * @param request 智能体创建请求对象
     * @return 创建结果
     */
    fun createAgent(request: AgentCreateRequest): Boolean

    /**
     * 更新智能体
     *
     * @param id      智能体 ID
     * @param request 智能体更新请求对象
     * @return 更新结果
     */
    fun updateAgent(id: Long, request: AgentUpdateRequest): Boolean

    /**
     * 切换智能体启用状态
     *
     * @param id     智能体 ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun toggleAgentStatus(id: Long, status: Int): Boolean

    /**
     * 删除智能体
     *
     * @param id 智能体 ID
     * @return 删除结果
     */
    fun deleteAgent(id: Long): Boolean

    /**
     * 将 Agent 实体转换为响应 DTO（包含完整的技能和 MCP 信息）
     *
     * @param agent 智能体实体
     * @return 响应 DTO
     */
    fun convertToResponse(agent: Agent): AgentResponse
}
