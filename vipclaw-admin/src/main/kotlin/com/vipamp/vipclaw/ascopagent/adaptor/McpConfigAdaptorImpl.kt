package com.vipamp.vipclaw.ascopagent.adaptor

import com.vipamp.vipclaw.admin.entity.McpServer
import com.vipamp.vipclaw.admin.mapper.McpServerMapper
import com.vipamp.vipclaw.agent.adaptor.McpConfigAdaptor
import com.vipamp.vipclaw.agent.adaptor.mcp.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * McpConfigAdaptor 实现类
 * 从数据库加载 MCP 配置并转换为 McpConfig
 */
@Component
class McpConfigAdaptorImpl(
    private val mcpServerMapper: McpServerMapper
) : McpConfigAdaptor {

    private val log = LoggerFactory.getLogger(McpConfigAdaptorImpl::class.java)

    override fun getConfig(mcpId: Long): McpConfig? {
        if (mcpId <= 0) {
            log.warn("Invalid mcpId: $mcpId")
            return null
        }

        // 查询 MCP 服务信息
        val mcpServer = mcpServerMapper.selectById(mcpId)
        if (mcpServer == null) {
            log.warn("McpServer not found: $mcpId")
            return null
        }

        // 根据 MCP 类型创建对应的配置
        return buildMcpConfig(mcpServer)
    }

    /**
     * 根据 MCP 类型构建 McpConfig
     */
    private fun buildMcpConfig(mcpServer: McpServer): McpConfig? {
        return when (val type = mcpServer.type.lowercase()) {
            "stdio" -> StdioMcpConfig(
                mcpServer.name,
                mcpServer.command,
                emptyList(),
                emptyMap()
            )
            "sse" -> SseHttpMcpConfig(
                mcpServer.name,
                mcpServer.url,
                emptyMap(),
                emptyMap()
            )
            "streamablehttp" -> StreamableHttpMcpConfig(
                mcpServer.name,
                mcpServer.url,
                emptyMap(),
                emptyMap()
            )
            else -> {
                log.warn("Unsupported MCP type: $type")
                null
            }
        }
    }
}
