package com.vipamp.vipclaw.agent.service.adaptor;

import com.vipamp.vipclaw.agent.adaptor.McpConfigAdaptor;
import com.vipamp.vipclaw.agent.adaptor.mcp.McpConfig;
import com.vipamp.vipclaw.agent.adaptor.mcp.SseHttpMcpConfig;
import com.vipamp.vipclaw.agent.adaptor.mcp.StdioMcpConfig;
import com.vipamp.vipclaw.agent.adaptor.mcp.StreamableHttpMcpConfig;
import com.vipamp.vipclaw.common.entity.McpServer;
import com.vipamp.vipclaw.common.mapper.McpServerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * McpConfigAdaptor 实现类
 * 从数据库加载 MCP 配置并转换为 McpConfig
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpConfigAdaptorImpl implements McpConfigAdaptor {

    private final McpServerMapper mcpServerMapper;

    @Override
    public McpConfig getConfig(long mcpId) {
        if (mcpId <= 0) {
            log.warn("Invalid mcpId: {}", mcpId);
            return null;
        }

        // 查询 MCP 服务信息
        McpServer mcpServer = mcpServerMapper.selectById(mcpId);
        if (mcpServer == null) {
            log.warn("McpServer not found: {}", mcpId);
            return null;
        }

        // 根据 MCP 类型创建对应的配置
        return buildMcpConfig(mcpServer);
    }

    /**
     * 根据 MCP 类型构建 McpConfig
     */
    private McpConfig buildMcpConfig(McpServer mcpServer) {
        String type = mcpServer.getType().toLowerCase();

        return switch (type) {
            case "stdio" -> new StdioMcpConfig(
                    mcpServer.getName(),
                    mcpServer.getCommand(),
                    Collections.emptyList(),
                    Collections.emptyMap()
            );
            case "sse" -> new SseHttpMcpConfig(
                    mcpServer.getName(),
                    mcpServer.getUrl(),
                    Collections.emptyMap(),
                    Collections.emptyMap()
            );
            case "streamablehttp" -> new StreamableHttpMcpConfig(
                    mcpServer.getName(),
                    mcpServer.getUrl(),
                    Collections.emptyMap(),
                    Collections.emptyMap()
            );
            default -> {
                log.warn("Unsupported MCP type: {}", type);
                yield null;
            }
        };
    }
}
