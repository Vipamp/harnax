package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Channel update request DTO
 */
@Schema(description = "Channel update request object")
data class ChannelUpdateRequest(
    @Schema(description = "ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "ID cannot be empty")
    var id: Long? = null,

    @Schema(description = "Channel name", example = "Enterprise WeChat Customer Service")
    @Size(max = 100, message = "Channel name length cannot exceed 100 characters")
    val name: String? = null,

    @Schema(description = "Type (wecom/feishu/dingtalk/http)", example = "wecom")
    val type: String? = null,

    @Schema(description = "Associated agent ID", example = "1")
    val agentId: Long? = null,

    @Schema(description = "Webhook URL", example = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx")
    @Size(max = 500, message = "Webhook URL length cannot exceed 500 characters")
    val webhookUrl: String? = null,

    @Schema(description = "Verification token", example = "my-token")
    @Size(max = 500, message = "Token length cannot exceed 500 characters")
    val token: String? = null,

    @Schema(description = "Encryption key (for WeCom)", example = "aes-key-32-characters")
    @Size(max = 500, message = "Encryption key length cannot exceed 500 characters")
    val encodingAesKey: String? = null,

    @Schema(description = "App ID (for Feishu/DingTalk)", example = "cli_xxx")
    @Size(max = 100, message = "App ID length cannot exceed 100 characters")
    val appId: String? = null,

    @Schema(description = "App secret", example = "app-secret")
    @Size(max = 500, message = "App secret length cannot exceed 500 characters")
    val appSecret: String? = null,

    @Schema(description = "Description", example = "Robot channel for customer service")
    val description: String? = null,

    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int? = null,
)
