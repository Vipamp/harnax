package com.vipamp.vipclaw.agent.adaptor.mcp

/**
 * MCP 配置接口
 * 支持多种 MCP 连接方式的配置
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: vipclaw
 */
sealed interface McpConfig {
    val name: String
}

/**
 * STDIO 类型的 MCP 配置
 * 通过标准输入输出与本地进程通信
 */
data class StdioMcpConfig(
    override val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = mapOf(),
) : McpConfig

/**
 * SSE HTTP 类型的 MCP 配置
 * 通过 SSE 事件流与远程 MCP 服务通信
 */
data class SseHttpMcpConfig(
    override val name: String,
    val url: String,
    val headers: Map<String, String> = mapOf(),
    val queryParam: Map<String, String> = mapOf(),
) : McpConfig

/**
 * Streamable HTTP 类型的 MCP 配置
 * 通过 HTTP 流式传输与远程 MCP 服务通信
 */
data class StreamableHttpMcpConfig(
    override val name: String,
    val url: String,
    val headers: Map<String, String> = mapOf(),
    val queryParam: Map<String, String> = mapOf(),
) : McpConfig
