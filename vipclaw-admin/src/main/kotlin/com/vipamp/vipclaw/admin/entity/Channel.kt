package com.vipamp.vipclaw.admin.entity

import com.baomidou.mybatisplus.annotation.*
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Channel 通道实体类
 *
 * @author vipamp
 * @since 2026-04-08
 */
@TableName("channel")
@Schema(description = "Channel 通道实体类")
class Channel : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "通道名称")
    var name: String = ""

    @Schema(description = "类型(wecom/feishu/dingtalk/http)")
    var type: String = ""

    @Schema(description = "关联的智能体ID")
    var agentId: Long = 0

    @Schema(description = "推送地址")
    var webhookUrl: String = ""

    @Schema(description = "验证Token")
    var token: String = ""

    @Schema(description = "加密密钥(企业微信)")
    var encodingAesKey: String = ""

    @Schema(description = "应用ID(飞书/钉钉)")
    var appId: String = ""

    @Schema(description = "应用密钥")
    var appSecret: String = ""

    @Schema(description = "回调标识(用于生成回调URL)")
    var callbackKey: String = ""

    @Schema(description = "描述")
    var description: String = ""

    @Schema(description = "是否启用（0:禁用，1:启用）")
    var status: Int = 1

    @Schema(description = "是否可用（0:被删除，1:可用）")
    @TableLogic(value = "1", delval = "0")
    var active: Int = 1

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    var updateTime: LocalDateTime = LocalDateTime.now()
}
