package com.vipamp.vipclaw.agent.adaptor

import com.vipamp.vipclaw.agent.adaptor.mcp.McpConfig

/**
 * MCP 配置适配器接口
 * 提供统一的配置获取方式
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: vipclaw
 */
fun interface McpConfigAdaptor {
    /**
     * 获取 MCP 配置
     *
     * @return McpConfig 实例
     */
    fun getConfig(mcpId: Long): McpConfig?
}
