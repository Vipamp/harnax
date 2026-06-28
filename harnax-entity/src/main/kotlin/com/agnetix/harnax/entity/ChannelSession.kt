package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * ChannelSession entity
 *
 * Schema structure mirrors the [Channel] table.
 * When a channel is created, a channel_session record is created to manage the agent session,
 * and the generated session_id is backfilled into the channel's session_id field.
 */
@Schema(description = "ChannelSession entity")
class ChannelSession : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Channel name")
    var name: String = ""

    @Schema(description = "Channel type (wecom/wechat/feishu/dingtalk/http)")
    var type: String = ""

    @Schema(description = "Associated agent ID")
    var agentId: Long = 0

    @Schema(description = "Callback key (used to generate callback URL)")
    var callbackKey: String = ""

    @Schema(description = "Associated session ID (UUID), generated at creation time")
    var sessionId: String = ""

    @Schema(description = "Communication mode (webhook/websocket/long_polling)")
    var communicationMode: String = "webhook"

    @Schema(description = "Whether to auto-listen on service startup (0:no, 1:yes)")
    var enabled: Int = 1

    @Schema(description = "Channel-specific config JSON")
    var configJson: String? = null

    @Schema(description = "Description")
    var description: String? = null

    @Schema(description = "Creator")
    var creator: String = "system"

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
