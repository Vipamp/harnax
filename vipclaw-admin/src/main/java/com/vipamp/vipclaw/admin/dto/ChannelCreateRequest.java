package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Channel 创建请求 DTO
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Data
@Schema(description = "Channel 创建请求对象")
public class ChannelCreateRequest {

    @Schema(description = "通道名称", example = "企业微信客服", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "通道名称不能为空")
    @Size(max = 100, message = "通道名称长度不能超过 100 个字符")
    private String name;

    @Schema(description = "类型(wecom/feishu/dingtalk/http)", example = "wecom", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "类型不能为空")
    private String type;

    @Schema(description = "关联的智能体ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "关联的智能体ID不能为空")
    private Long agentId;

    @Schema(description = "推送地址", example = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx")
    @Size(max = 500, message = "推送地址长度不能超过 500 个字符")
    private String webhookUrl;

    @Schema(description = "验证Token", example = "my-token")
    @Size(max = 500, message = "Token长度不能超过 500 个字符")
    private String token;

    @Schema(description = "加密密钥(企业微信)", example = "aes-key-32-characters")
    @Size(max = 500, message = "加密密钥长度不能超过 500 个字符")
    private String encodingAesKey;

    @Schema(description = "应用ID(飞书/钉钉)", example = "cli_xxx")
    @Size(max = 100, message = "应用ID长度不能超过 100 个字符")
    private String appId;

    @Schema(description = "应用密钥", example = "app-secret")
    @Size(max = 500, message = "应用密钥长度不能超过 500 个字符")
    private String appSecret;

    @Schema(description = "描述", example = "用于客户服务的机器人通道")
    private String description;

    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    private Integer status;
}
