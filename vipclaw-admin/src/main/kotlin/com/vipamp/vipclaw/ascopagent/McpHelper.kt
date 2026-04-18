package com.vipamp.vipclaw.ascopagent

import com.vipamp.vipclaw.agent.adaptor.mcp.McpConfig
import com.vipamp.vipclaw.agent.adaptor.mcp.McpErrorCode
import com.vipamp.vipclaw.agent.adaptor.mcp.SseHttpMcpConfig
import com.vipamp.vipclaw.agent.adaptor.mcp.StdioMcpConfig
import com.vipamp.vipclaw.agent.adaptor.mcp.StreamableHttpMcpConfig
import com.vipamp.vipclaw.common.log.logger
import io.agentscope.core.tool.mcp.McpClientBuilder
import io.agentscope.core.tool.mcp.McpClientWrapper
import io.modelcontextprotocol.spec.McpSchema

/**
 * MCP 客户端辅助工具类
 * 提供基于 McpConfig 创建 McpClientBuilder 的方法
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: vipclaw
 */
object McpHelper {

    fun listTools(mcpConfig: McpConfig): List<McpSchema.Tool> {
        val mcpClient = createMcpClient(mcpConfig, false)
        try {
            mcpClient.initialize()?.block()
        } catch (t: Throwable) {
            logger().error("Failed to initialize McpClient `${mcpConfig.name}`", t)
            throw McpErrorCode.MCP_CONNECTION_FAILED.format(t, mcpConfig.name)
        }
        try {
            return mcpClient.listTools()?.block() ?: emptyList()
        } catch (t: Throwable) {
            logger().error("Failed to list tools from McpClient `${mcpConfig.name}`", t)
            throw McpErrorCode.MCP_CONNECTION_FAILED.format(t, mcpConfig.name)
        }
    }

    /**
     * 根据 MCP 配置创建 MCP 客户端
     *
     * @param mcpConfig MCP 配置，支持 StdioMcpConfig、SseHttpMcpConfig、StreamableHttpMcpConfig
     * @param isAsync 是否异步创建客户端
     * @return McpClientWrapper 实例
     * @throws McpErrorCode.MCP_CLIENT_CREATE_FAILED 当客户端创建失败时抛出
     */
    fun createMcpClient(
        mcpConfig: McpConfig,
        isAsync: Boolean
    ): McpClientWrapper {
        val builder = when (mcpConfig) {
            is StdioMcpConfig -> buildStdioMcpClient(mcpConfig)
            is SseHttpMcpConfig -> buildSseMcpClient(mcpConfig)
            is StreamableHttpMcpConfig -> buildStreamableMcpClient(mcpConfig)
        }
        return try {
            if (isAsync) builder.buildAsync().block()!! else builder.buildSync()
        } catch (e: Exception) {
            throw McpErrorCode.MCP_CLIENT_CREATE_FAILED.format(e, mcpConfig.name)
        }
    }

    /**
     * 构建 STDIO 类型的 MCP 客户端 Builder
     *
     * @param mcpConfig STDIO 类型的 MCP 配置
     * @return McpClientBuilder 实例
     */
    fun buildStdioMcpClient(mcpConfig: StdioMcpConfig): McpClientBuilder =
        McpClientBuilder.create(mcpConfig.name)
            .stdioTransport(mcpConfig.command, mcpConfig.args, mcpConfig.env)

    /**
     * 构建 SSE HTTP 类型的 MCP 客户端 Builder
     *
     * @param mcpConfig SSE HTTP 类型的 MCP 配置
     * @return McpClientBuilder 实例
     */
    fun buildSseMcpClient(mcpConfig: SseHttpMcpConfig): McpClientBuilder =
        McpClientBuilder.create(mcpConfig.name)
            .sseTransport(mcpConfig.url)
            .applyHttpTransport(mcpConfig.headers, mcpConfig.queryParam)

    /**
     * 构建 Streamable HTTP 类型的 MCP 客户端 Builder
     *
     * @param mcpConfig Streamable HTTP 类型的 MCP 配置
     * @return McpClientBuilder 实例
     */
    fun buildStreamableMcpClient(mcpConfig: StreamableHttpMcpConfig): McpClientBuilder =
        McpClientBuilder.create(mcpConfig.name)
            .streamableHttpTransport(mcpConfig.url)
            .applyHttpTransport(mcpConfig.headers, mcpConfig.queryParam)

    /**
     * 扩展函数：应用 HTTP 传输配置
     *
     * @param headers HTTP 请求头
     * @param queryParam URL 查询参数
     * @return McpClientBuilder 实例
     */
    fun McpClientBuilder.applyHttpTransport(
        headers: Map<String, String>,
        queryParam: Map<String, String>
    ): McpClientBuilder =
        apply {
            if (headers.isNotEmpty()) headers(headers)
            if (queryParam.isNotEmpty()) queryParams(queryParam)
        }
}
