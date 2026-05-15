package com.vipamp.vipclaw.ascopagent

import com.vipamp.vipclaw.agent.adaptor.mcp.*
import io.agentscope.core.tool.mcp.McpClientBuilder
import io.agentscope.core.tool.mcp.McpClientWrapper
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory

/**
 * MCP Client Helper Utility
 * Provides methods to create McpClientBuilder based on McpConfig
 */
object McpHelper {

    private val log = LoggerFactory.getLogger(McpHelper::class.java)

    fun listTools(mcpConfig: McpConfig): List<McpSchema.Tool> {
        val mcpClient = createMcpClient(mcpConfig, false)
        try {
            mcpClient.initialize()?.block()
        } catch (t: Throwable) {
            log.error("Failed to initialize McpClient `${mcpConfig.name}`", t)
            throw McpErrorCode.MCP_CONNECTION_FAILED.format(t, mcpConfig.name)
        }
        try {
            return mcpClient.listTools()?.block() ?: emptyList()
        } catch (t: Throwable) {
            log.error("Failed to list tools from McpClient `${mcpConfig.name}`", t)
            throw McpErrorCode.MCP_CONNECTION_FAILED.format(t, mcpConfig.name)
        }
    }

    /**
     * Create MCP client based on configuration
     *
     * @param mcpConfig MCP configuration, supports StdioMcpConfig, SseHttpMcpConfig, StreamableHttpMcpConfig
     * @param isAsync Whether to create client asynchronously
     * @return McpClientWrapper instance
     * @throws McpErrorCode.MCP_CLIENT_CREATE_FAILED Thrown when client creation fails
     */
    fun createMcpClient(
        mcpConfig: McpConfig,
        isAsync: Boolean,
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
     * Build STDIO type MCP client Builder
     *
     * @param mcpConfig STDIO type MCP configuration
     * @return McpClientBuilder instance
     */
    fun buildStdioMcpClient(mcpConfig: StdioMcpConfig): McpClientBuilder = McpClientBuilder.create(mcpConfig.name)
        .stdioTransport(mcpConfig.command, mcpConfig.args, mcpConfig.env)

    /**
     * Build SSE HTTP type MCP client Builder
     *
     * @param mcpConfig SSE HTTP type MCP configuration
     * @return McpClientBuilder instance
     */
    fun buildSseMcpClient(mcpConfig: SseHttpMcpConfig): McpClientBuilder = McpClientBuilder.create(mcpConfig.name)
        .sseTransport(mcpConfig.url)
        .applyHttpTransport(mcpConfig.headers, mcpConfig.queryParam)

    /**
     * Build Streamable HTTP type MCP client Builder
     *
     * @param mcpConfig Streamable HTTP type MCP configuration
     * @return McpClientBuilder instance
     */
    fun buildStreamableMcpClient(mcpConfig: StreamableHttpMcpConfig): McpClientBuilder = McpClientBuilder.create(mcpConfig.name)
        .streamableHttpTransport(mcpConfig.url)
        .applyHttpTransport(mcpConfig.headers, mcpConfig.queryParam)

    /**
     * Extension function: Apply HTTP transport configuration
     *
     * @param headers HTTP request headers
     * @param queryParam URL query parameters
     * @return McpClientBuilder instance
     */
    fun McpClientBuilder.applyHttpTransport(
        headers: Map<String, String>,
        queryParam: Map<String, String>,
    ): McpClientBuilder = apply {
        if (headers.isNotEmpty()) headers(headers)
        if (queryParam.isNotEmpty()) queryParams(queryParam)
    }
}
