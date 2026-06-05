package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.Channel
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Channel response DTO
 */
@Schema(description = "Channel response object")
data class ChannelResponse(
    @Schema(description = "ID")
    val id: Long? = null,

    @Schema(description = "Tenant ID")
    val tenantId: Long? = null,

    @Schema(description = "Channel name")
    val name: String? = null,

    @Schema(description = "Channel type (wecom/wechat/feishu/dingtalk/http)")
    val type: String? = null,

    @Schema(description = "Type display name")
    val typeDisplayName: String? = null,

    @Schema(description = "Associated agent ID")
    val agentId: Long? = null,

    @Schema(description = "Agent name")
    var agentName: String? = null,

    @Schema(description = "Callback key (used to generate callback URL)")
    val callbackKey: String? = null,

    @Schema(description = "Callback URL")
    var callbackUrl: String? = null,

    @Schema(description = "Communication mode (webhook/websocket/long_polling)")
    val communicationMode: String? = null,

    @Schema(description = "Whether to auto-listen on service startup (0:no, 1:yes)")
    val enabled: Int? = null,

    @Schema(description = "Channel-specific configuration JSON")
    val configJson: String? = null,

    @Schema(description = "Description")
    val description: String? = null,

    @Schema(description = "Creator")
    val creator: String? = null,

    @Schema(description = "Status (0:disabled, 1:enabled)")
    val status: Int? = null,

    @Schema(description = "Creation time")
    val createTime: LocalDateTime? = null,

    @Schema(description = "Update time")
    val updateTime: LocalDateTime? = null,
) {
    companion object {
        /**
         * Get type display name
         */
        fun getTypeDisplayName(type: String?): String = when (type) {
            "wecom" -> "Enterprise WeChat"
            "wechat" -> "WeChat"
            "feishu" -> "Feishu"
            "dingtalk" -> "DingTalk"
            "http" -> "HTTP Interface"
            else -> type ?: ""
        }

        /**
         * Convert from entity object
         */
        fun fromEntity(channel: Channel): ChannelResponse = ChannelResponse(
            id = channel.id,
            tenantId = channel.tenantId,
            name = channel.name,
            type = channel.type,
            typeDisplayName = getTypeDisplayName(channel.type),
            agentId = channel.agentId,
            callbackKey = channel.callbackKey,
            communicationMode = channel.communicationMode,
            enabled = channel.enabled,
            configJson = channel.configJson,
            description = channel.description,
            creator = channel.creator,
            status = channel.status,
            createTime = channel.createTime,
            updateTime = channel.updateTime,
        )
    }
}
