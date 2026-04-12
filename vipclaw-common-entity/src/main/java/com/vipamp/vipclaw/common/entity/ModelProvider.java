package com.vipamp.vipclaw.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模型供应商实体类
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("model_provider")
@Schema(description = "模型供应商实体类")
public class ModelProvider implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    private Long id;

    /**
     * 服务商名称（dashscope/openai/ollama）
     */
    @Schema(description = "服务商名称（dashscope/openai/ollama）")
    private String name;

    /**
     * 显示名称
     */
    @Schema(description = "显示名称")
    private String displayName;

    /**
     * API 密钥
     */
    @Schema(description = "API 密钥")
    private String apiKey;

    /**
     * API 地址
     */
    @Schema(description = "API 地址")
    private String baseUrl;

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）")
    private Integer status;

    /**
     * 是否公开（0:否，1:是）
     */
    @Schema(description = "是否公开（0:否，1:是）")
    private Integer isPublic;

    /**
     * 创建人
     */
    @Schema(description = "创建人")
    private String creator;

    /**
     * 是否可用（0:被删除，1:可用）
     */
    @Schema(description = "是否可用（0:被删除，1:可用）")
    @TableLogic(value = "1", delval = "0")
    private Integer active;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
