package com.agnetix.harnax.ascopagent.adaptor

import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.mcp.*
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.mapper.McpServerMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * McpConfigAdaptor Implementation
 * Loads MCP configuration from database and converts to McpConfig
 */
@Component
class McpConfigAdaptorImpl(
    private val mcpServerMapper: McpServerMapper,
) : McpConfigAdaptor {

    private val log = LoggerFactory.getLogger(McpConfigAdaptorImpl::class.java)

    override fun getConfig(mcpId: Long): McpConfig? {
        if (mcpId <= 0) {
            log.warn("Invalid mcpId: $mcpId")
            return null
        }

        // Query MCP server information
        val mcpServer = mcpServerMapper.selectById(mcpId)
        if (mcpServer == null) {
            log.warn("McpServer not found: $mcpId")
            return null
        }

        // Create corresponding configuration based on MCP type
        return buildMcpConfig(mcpServer)
    }

    /**
     * Build McpConfig based on MCP type
     */
    private fun buildMcpConfig(mcpServer: McpServer): McpConfig? = when (val type = mcpServer.type.lowercase()) {
        "stdio" -> StdioMcpConfig(
            mcpServer.name,
            mcpServer.command,
            emptyList(),
            emptyMap(),
        )
        "sse" -> SseHttpMcpConfig(
            mcpServer.name,
            mcpServer.url,
            emptyMap(),
            emptyMap(),
        )
        "streamablehttp" -> StreamableHttpMcpConfig(
            mcpServer.name,
            mcpServer.url,
            emptyMap(),
            emptyMap(),
        )
        else -> {
            log.warn("Unsupported MCP type: $type")
            null
        }
    }
}
