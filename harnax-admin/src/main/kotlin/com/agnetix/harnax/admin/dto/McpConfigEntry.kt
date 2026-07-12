package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 通用配置条目（headers 和 envParams 共用）
 * 用于 MCP 服务的 HTTP 请求头和环境参数配置
 */
@Schema(description = "MCP 配置条目")
data class McpConfigEntry(
    @Schema(description = "配置键名", example = "Authorization")
    val key: String = "",

    @Schema(description = "配置值", example = "Bearer sk-xxx")
    val value: String = "",

    @Schema(description = "是否为敏感值（true 则加密存储 + 脱敏返回）", example = "false")
    val secret: Boolean = false,
)
