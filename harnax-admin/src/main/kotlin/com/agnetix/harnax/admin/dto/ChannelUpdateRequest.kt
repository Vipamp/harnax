package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * Channel update request DTO
 */
@Schema(description = "Channel update request object")
data class ChannelUpdateRequest(
    @Schema(description = "Channel name", example = "Enterprise WeChat Customer Service")
    @Size(max = 100, message = "Channel name length cannot exceed 100 characters")
    val name: String? = null,

    @Schema(description = "Channel type (wecom/wechat/feishu/dingtalk/http)", example = "wecom")
    val type: String? = null,

    @Schema(description = "Associated agent ID", example = "1")
    val agentId: Long? = null,

    @Schema(description = "Communication mode (webhook/websocket/long_polling)", example = "webhook")
    @Size(max = 20)
    val communicationMode: String? = null,

    @Schema(description = "Whether to auto-listen on service startup (0:no, 1:yes)", example = "1")
    val enabled: Int? = null,

    @Schema(description = "Channel-specific configuration JSON, e.g. {\"appId\":\"xxx\",\"appSecret\":\"xxx\"}")
    val configJson: String? = null,

    @Schema(description = "Description", example = "Robot channel for customer service")
    val description: String? = null,

    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int? = null,
)
