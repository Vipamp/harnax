package com.agnetix.harnax.agent.adaptor.mcp

import com.agnetix.harnax.entity.McpServer
import io.agentscope.core.tool.mcp.McpClientBuilder
import io.agentscope.core.tool.mcp.McpClientWrapper
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory

/**
 * 配置解析器函数类型：将数据库中存储的 JSON 字符串解析为明文的 Key-Value Map
 * 实现方负责反序列化 + 解密 secret 条目
 */
typealias McpConfigResolver = (String?) -> Map<String, String>

/**
 * MCP 客户端辅助工具类
 * 提供基于 McpConfig 创建 McpClientBuilder 的方法
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: harnax
 */
object McpHelper {

    private val log = LoggerFactory.getLogger(McpHelper::class.java)

    fun listTools(mcpServer: McpServer, configResolver: McpConfigResolver? = null, envResolver: McpConfigResolver? = null): List<McpSchema.Tool> {
        val mcpClient = createMcpClient(mcpServer, false, configResolver, envResolver)
        try {
            mcpClient.initialize()?.block(java.time.Duration.ofSeconds(10))
        } catch (t: Throwable) {
            log.error("Failed to initialize McpClient `${mcpServer.name}`", t)
            throw McpErrorCode.MCP_CONNECTION_FAILED.format(t, mcpServer.name)
        }
        try {
            return mcpClient.listTools()?.block() ?: emptyList()
        } catch (t: Throwable) {
            log.error("Failed to list tools from McpClient `${mcpServer.name}`", t)
            throw McpErrorCode.MCP_CONNECTION_FAILED.format(t, mcpServer.name)
        }
    }

    /**
     * Build McpConfig based on MCP type
     *
     * @param mcpServer MCP server entity
     * @param configResolver optional resolver to deserialize + decrypt headers/envParams JSON to plain Map
     */
    fun buildMcpConfig(mcpServer: McpServer, configResolver: McpConfigResolver? = null, envResolver: McpConfigResolver? = null): McpConfig? {
        val resolve: McpConfigResolver = configResolver ?: { emptyMap() }
        val resolveEnv: McpConfigResolver = envResolver ?: resolve
        return when (val type = mcpServer.type.lowercase()) {
            "stdio" -> StdioMcpConfig(
                mcpServer.name,
                mcpServer.command,
                emptyList(),
                resolveEnv(mcpServer.envParams),
            )
            "sse" -> SseHttpMcpConfig(
                mcpServer.name,
                mcpServer.url,
                resolve(mcpServer.headers),
                emptyMap(),
            )
            "streamablehttp" -> StreamableHttpMcpConfig(
                mcpServer.name,
                mcpServer.url,
                resolve(mcpServer.headers),
                emptyMap(),
            )
            else -> {
                log.warn("Unsupported MCP type: $type")
                null
            }
        }
    }

    /**
     * 根据 MCP 配置创建 MCP 客户端
     *
     * @param mcpServer MCP 服务实体
     * @param isAsync 是否异步创建客户端
     * @param configResolver optional resolver to deserialize + decrypt headers/envParams JSON to plain Map
     * @return McpClientWrapper 实例
     * @throws McpErrorCode.MCP_CLIENT_CREATE_FAILED 当客户端创建失败时抛出
     */
    fun createMcpClient(
        mcpServer: McpServer,
        isAsync: Boolean,
        configResolver: McpConfigResolver? = null,
        envResolver: McpConfigResolver? = null,
    ): McpClientWrapper {
        val mcpConfig = buildMcpConfig(mcpServer, configResolver, envResolver) ?: throw McpErrorCode.MCP_CLIENT_CREATE_FAILED.format("MCP config is null")
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
    fun buildStdioMcpClient(mcpConfig: StdioMcpConfig): McpClientBuilder = McpClientBuilder.create(mcpConfig.name)
        .stdioTransport(mcpConfig.command, mcpConfig.args, mcpConfig.env)

    /**
     * 构建 SSE HTTP 类型的 MCP 客户端 Builder
     *
     * @param mcpConfig SSE HTTP 类型的 MCP 配置
     * @return McpClientBuilder 实例
     */
    fun buildSseMcpClient(mcpConfig: SseHttpMcpConfig): McpClientBuilder = McpClientBuilder.create(mcpConfig.name)
        .sseTransport(mcpConfig.url)
        .applyHttpTransport(mcpConfig.headers, mcpConfig.queryParam)

    /**
     * 构建 Streamable HTTP 类型的 MCP 客户端 Builder
     *
     * @param mcpConfig Streamable HTTP 类型的 MCP 配置
     * @return McpClientBuilder 实例
     */
    fun buildStreamableMcpClient(mcpConfig: StreamableHttpMcpConfig): McpClientBuilder = McpClientBuilder.create(mcpConfig.name)
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
        queryParam: Map<String, String>,
    ): McpClientBuilder = apply {
        if (headers.isNotEmpty()) headers(headers)
        if (queryParam.isNotEmpty()) queryParams(queryParam)
    }
}
