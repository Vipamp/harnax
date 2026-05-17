package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.admin.entity.Channel
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Channel response DTO
 */
@Schema(description = "Channel response object")
data class ChannelResponse(
    @Schema(description = "ID")
    val id: Long? = null,

    @Schema(description = "Channel name")
    val name: String? = null,

    @Schema(description = "Type (wecom/feishu/dingtalk/http)")
    val type: String? = null,

    @Schema(description = "Type display name")
    val typeDisplayName: String? = null,

    @Schema(description = "Associated agent ID")
    val agentId: Long? = null,

    @Schema(description = "Agent name")
    var agentName: String? = null,

    @Schema(description = "Webhook URL")
    val webhookUrl: String? = null,

    @Schema(description = "Verification token")
    val token: String? = null,

    @Schema(description = "Encryption key (for WeCom)")
    val encodingAesKey: String? = null,

    @Schema(description = "App ID (for Feishu/DingTalk)")
    val appId: String? = null,

    @Schema(description = "App secret")
    val appSecret: String? = null,

    @Schema(description = "Callback key (used to generate callback URL)")
    val callbackKey: String? = null,

    @Schema(description = "Callback URL")
    var callbackUrl: String? = null,

    @Schema(description = "Description")
    val description: String? = null,

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
            name = channel.name,
            type = channel.type,
            typeDisplayName = getTypeDisplayName(channel.type),
            agentId = channel.agentId,
            webhookUrl = channel.webhookUrl,
            token = channel.token,
            encodingAesKey = channel.encodingAesKey,
            appId = channel.appId,
            appSecret = channel.appSecret,
            callbackKey = channel.callbackKey,
            description = channel.description,
            status = channel.status,
            createTime = channel.createTime,
            updateTime = channel.updateTime,
        )
    }
}
