package com.vipamp.vipclaw.admin.dto;

import com.vipamp.vipclaw.admin.entity.Channel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Channel 响应 DTO
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Data
@Schema(description = "Channel 响应对象")
public class ChannelResponse {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "通道名称")
    private String name;

    @Schema(description = "类型(wecom/feishu/dingtalk/http)")
    private String type;

    @Schema(description = "类型显示名称")
    private String typeDisplayName;

    @Schema(description = "关联的智能体ID")
    private Long agentId;

    @Schema(description = "智能体名称")
    private String agentName;

    @Schema(description = "推送地址")
    private String webhookUrl;

    @Schema(description = "验证Token")
    private String token;

    @Schema(description = "加密密钥(企业微信)")
    private String encodingAesKey;

    @Schema(description = "应用ID(飞书/钉钉)")
    private String appId;

    @Schema(description = "应用密钥")
    private String appSecret;

    @Schema(description = "回调标识(用于生成回调URL)")
    private String callbackKey;

    @Schema(description = "回调URL")
    private String callbackUrl;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "是否启用（0:禁用，1:启用）")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /**
     * 获取类型显示名称
     */
    public String getTypeDisplayName() {
        if (type == null) return "";
        return switch (type) {
            case "wecom" -> "企业微信";
            case "feishu" -> "飞书";
            case "dingtalk" -> "钉钉";
            case "http" -> "HTTP接口";
            default -> type;
        };
    }

    /**
     * 从实体对象转换
     */
    public static ChannelResponse fromEntity(Channel channel) {
        if (channel == null) {
            return null;
        }
        ChannelResponse response = new ChannelResponse();
        response.setId(channel.getId());
        response.setName(channel.getName());
        response.setType(channel.getType());
        response.setAgentId(channel.getAgentId());
        response.setWebhookUrl(channel.getWebhookUrl());
        response.setToken(channel.getToken());
        response.setEncodingAesKey(channel.getEncodingAesKey());
        response.setAppId(channel.getAppId());
        response.setAppSecret(channel.getAppSecret());
        response.setCallbackKey(channel.getCallbackKey());
        response.setDescription(channel.getDescription());
        response.setStatus(channel.getStatus());
        response.setCreateTime(channel.getCreateTime());
        response.setUpdateTime(channel.getUpdateTime());
        return response;
    }
}