package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Channel entity
 */
@Schema(description = "Channel entity")
class Channel : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Channel name")
    var name: String = ""

    @Schema(description = "Type (wecom/feishu/dingtalk/http)")
    var type: String = ""

    @Schema(description = "Associated agent ID")
    var agentId: Long = 0

    @Schema(description = "Webhook URL")
    var webhookUrl: String = ""

    @Schema(description = "Verification token")
    var token: String = ""

    @Schema(description = "Encryption key (WeCom)")
    var encodingAesKey: String = ""

    @Schema(description = "App ID (Feishu/DingTalk)")
    var appId: String = ""

    @Schema(description = "App secret")
    var appSecret: String = ""

    @Schema(description = "Callback key (used to generate callback URL)")
    var callbackKey: String = ""

    @Schema(description = "Description")
    var description: String = ""

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
