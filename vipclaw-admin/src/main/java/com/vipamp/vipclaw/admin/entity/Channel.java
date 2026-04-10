package com.vipamp.vipclaw.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Channel 通道实体类
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("channel")
@Schema(description = "Channel 通道实体类")
public class Channel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    private Long id;

    @Schema(description = "通道名称")
    private String name;

    @Schema(description = "类型(wecom/feishu/dingtalk/http)")
    private String type;

    @Schema(description = "关联的智能体ID")
    private Long agentId;

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

    @Schema(description = "描述")
    private String description;

    @Schema(description = "是否启用（0:禁用，1:启用）")
    private Integer status;

    @Schema(description = "是否可用（0:被删除，1:可用）")
    @TableLogic(value = "1", delval = "0")
    private Integer active;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
