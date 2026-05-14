package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.Channel
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Channel 响应 DTO
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Schema(description = "Channel 响应对象")
data class ChannelResponse(
    @Schema(description = "ID")
    val id: Long? = null,

    @Schema(description = "通道名称")
    val name: String? = null,

    @Schema(description = "类型(wecom/feishu/dingtalk/http)")
    val type: String? = null,

    @Schema(description = "类型显示名称")
    val typeDisplayName: String? = null,

    @Schema(description = "关联的智能体ID")
    val agentId: Long? = null,

    @Schema(description = "智能体名称")
    var agentName: String? = null,

    @Schema(description = "推送地址")
    val webhookUrl: String? = null,

    @Schema(description = "验证Token")
    val token: String? = null,

    @Schema(description = "加密密钥(企业微信)")
    val encodingAesKey: String? = null,

    @Schema(description = "应用ID(飞书/钉钉)")
    val appId: String? = null,

    @Schema(description = "应用密钥")
    val appSecret: String? = null,

    @Schema(description = "回调标识(用于生成回调URL)")
    val callbackKey: String? = null,

    @Schema(description = "回调URL")
    var callbackUrl: String? = null,

    @Schema(description = "描述")
    val description: String? = null,

    @Schema(description = "是否启用（0:禁用，1:启用）")
    val status: Int? = null,

    @Schema(description = "Creation time")
    val createTime: LocalDateTime? = null,

    @Schema(description = "Update time")
    val updateTime: LocalDateTime? = null,
) {
    companion object {
        /**
         * 获取类型显示名称
         */
        fun getTypeDisplayName(type: String?): String = when (type) {
            "wecom" -> "企业微信"
            "feishu" -> "飞书"
            "dingtalk" -> "钉钉"
            "http" -> "HTTP接口"
            else -> type ?: ""
        }

        /**
         * 从实体对象转换
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
