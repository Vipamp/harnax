package com.vipamp.vipclaw.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模型实体类
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("model")
@Schema(description = "模型实体类")
public class Model implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    private Long id;

    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;

    /**
     * 模型名称
     */
    @Schema(description = "模型名称")
    private String modelName;

    /**
     * 模型供应商ID
     */
    @Schema(description = "模型供应商ID")
    private Long providerId;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 模型类型（chat/embedding）
     */
    @Schema(description = "模型类型（chat/embedding）")
    private String modelType;

    /**
     * 是否支持联网（0:否，1:是）
     */
    @Schema(description = "是否支持联网（0:否，1:是）")
    private Integer supportInternet;

    /**
     * 是否支持推理（0:否，1:是）
     */
    @Schema(description = "是否支持推理（0:否，1:是）")
    private Integer supportReasoning;

    /**
     * 是否支持工具（0:否，1:是）
     */
    @Schema(description = "是否支持工具（0:否，1:是）")
    private Integer supportTool;

    /**
     * 是否支持MCP（0:否，1:是）
     */
    @Schema(description = "是否支持MCP（0:否，1:是）")
    private Integer supportMcp;

    /**
     * 是否支持视觉（0:否，1:是）
     */
    @Schema(description = "是否支持视觉（0:否，1:是）")
    private Integer supportVision;

    /**
     * 价格（元/百万token）
     */
    @Schema(description = "价格（元/百万token）")
    private Double price;

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
