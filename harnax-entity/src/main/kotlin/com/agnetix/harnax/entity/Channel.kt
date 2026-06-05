package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Channel entity
 *
 * 公共业务字段 + 渠道差异化配置 JSON。
 * 不同渠道（飞书/微信/企业微信/钉钉/HTTP）特有的 appId/appSecret/encodingAesKey/
 * webhookUrl/token 等扩展字段，统一序列化到 [configJson] 字段中存储。
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

    @Schema(description = "Channel type (wecom/wechat/feishu/dingtalk/http)")
    var type: String = ""

    @Schema(description = "Associated agent ID")
    var agentId: Long = 0

    @Schema(description = "Callback key (used to generate callback URL)")
    var callbackKey: String = ""

    @Schema(description = "Communication mode (webhook/websocket/long_polling)")
    var communicationMode: String = "webhook"

    @Schema(description = "Whether to auto-listen on service startup (0:no, 1:yes)")
    var enabled: Int = 1

    @Schema(description = "Channel-specific config JSON, e.g. {appId,appSecret,encodingAesKey,webhookUrl,token,...}")
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
