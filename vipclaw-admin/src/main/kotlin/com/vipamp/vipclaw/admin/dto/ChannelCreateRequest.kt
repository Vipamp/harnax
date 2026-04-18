package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Channel 创建请求 DTO
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Schema(description = "Channel 创建请求对象")
data class ChannelCreateRequest(
    @Schema(description = "通道名称", example = "企业微信客服", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "通道名称不能为空")
    @Size(max = 100, message = "通道名称长度不能超过 100 个字符")
    val name: String? = null,

    @Schema(description = "类型(wecom/feishu/dingtalk/http)", example = "wecom", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "类型不能为空")
    val type: String? = null,

    @Schema(description = "关联的智能体ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "关联的智能体ID不能为空")
    val agentId: Long? = null,

    @Schema(description = "推送地址", example = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx")
    @Size(max = 500, message = "推送地址长度不能超过 500 个字符")
    val webhookUrl: String? = null,

    @Schema(description = "验证Token", example = "my-token")
    @Size(max = 500, message = "Token长度不能超过 500 个字符")
    val token: String? = null,

    @Schema(description = "加密密钥(企业微信)", example = "aes-key-32-characters")
    @Size(max = 500, message = "加密密钥长度不能超过 500 个字符")
    val encodingAesKey: String? = null,

    @Schema(description = "应用ID(飞书/钉钉)", example = "cli_xxx")
    @Size(max = 100, message = "应用ID长度不能超过 100 个字符")
    val appId: String? = null,

    @Schema(description = "应用密钥", example = "app-secret")
    @Size(max = 500, message = "应用密钥长度不能超过 500 个字符")
    val appSecret: String? = null,

    @Schema(description = "描述", example = "用于客户服务的机器人通道")
    val description: String? = null,

    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    val status: Int? = null
)
