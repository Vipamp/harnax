package com.vipamp.vipclaw.agent.adaptor

/**
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Description: McpConfig
 * @Project: vipclaw
 */
sealed interface McpConfig

data class StdioMcpConfig(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = mapOf()
) : McpConfig

data class SseHttpMcpConfig(
    val name: String,
    val url: String,
    val headers: Map<String, String> = mapOf(),
    val queryParam: Map<String, String> = mapOf()
) : McpConfig

data class StreamableHttpMcpConfig(
    val name: String,
    val url: String,
    val headers: Map<String, String> = mapOf(),
    val queryParam: Map<String, String> = mapOf()
) : McpConfig

val McpConfig.name: String
    get() = when (this) {
        is StdioMcpConfig -> this.name
        is SseHttpMcpConfig -> this.name
        is StreamableHttpMcpConfig -> this.name
    }
