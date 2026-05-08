package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.McpServerCreateRequest
import com.vipamp.vipclaw.admin.dto.McpServerResponse
import com.vipamp.vipclaw.admin.dto.McpServerUpdateRequest
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.McpServer
import io.modelcontextprotocol.spec.McpSchema

/**
 * MCP 服务接口
 *
 * @author vipamp
 * @since 2026-03-12
 */
interface McpServerService {

    /**
     * 分页查询 MCP 服务列表
     *
     * @param keyword  模糊查询字段（名称/描述）
     * @param status   状态筛选字段
     * @param type     类型筛选字段（如：stdio/sse/streamablehttp）
     * @param pageNum  当前页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    fun page(keyword: String?, status: Int?, type: String?, pageNum: Int, pageSize: Int): Page<McpServer>

    /**
     * 获取单个 MCP 服务详情
     *
     * @param id MCP ID
     * @return MCP 服务实体
     */
    fun getMcpServer(id: Long): McpServer?

    /**
     * 创建 MCP 服务
     *
     * @param request MCP 服务创建请求对象
     * @return 创建结果
     */
    fun createMcpServer(request: McpServerCreateRequest): Boolean

    /**
     * 更新 MCP 服务
     *
     * @param id      MCP ID
     * @param request MCP 服务更新请求对象
     * @return 更新结果
     */
    fun updateMcpServer(id: Long, request: McpServerUpdateRequest): Boolean

    /**
     * 切换 MCP 服务启用状态
     *
     * @param id     MCP ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun toggleMcpServerStatus(id: Long, status: Int): Boolean

    /**
     * 删除 MCP 服务（逻辑删除）
     *
     * @param id MCP ID
     * @return 删除结果
     */
    fun deleteMcpServer(id: Long): Boolean

    /**
     * MCP 服务连通性测试
     *
     * @param id MCP ID
     * @return 连通测试结果（true: 成功，false: 失败）
     */
    fun connectivityTest(id: Long): Boolean

    /**
     * 将 MCP 服务实体转换为响应对象
     *
     * @param mcpServer MCP 服务实体
     * @return MCP 服务响应对象
     */
    fun convertToResponse(mcpServer: McpServer): McpServerResponse

    /**
     * 列出 MCP 配置中的工具列表
     *
     * @param mcpConfig MCP 配置对象
     * @return 工具列表
     */
    fun listTools(mcpId: Long): List<McpSchema.Tool>
}
