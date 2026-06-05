package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Channel creation request DTO
 */
@Schema(description = "Channel creation request object")
data class ChannelCreateRequest(
    @Schema(description = "Channel name", example = "Enterprise WeChat Customer Service", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Channel name cannot be empty")
    @Size(max = 100, message = "Channel name length cannot exceed 100 characters")
    val name: String? = null,

    @Schema(description = "Channel type (wecom/wechat/feishu/dingtalk/http)", example = "wecom", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Type cannot be empty")
    val type: String? = null,

    @Schema(description = "Associated agent ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Associated agent ID cannot be empty")
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
