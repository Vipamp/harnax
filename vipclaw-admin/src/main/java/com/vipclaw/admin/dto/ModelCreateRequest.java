package com.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模型创建请求 DTO
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Data
@Schema(description = "模型创建请求对象")
public class ModelCreateRequest {

    /**
     * 名称
     */
    @Schema(description = "名称", example = "GPT-4", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "名称不能为空")
    @Size(max = 100, message = "名称长度不能超过 100 个字符")
    private String name;

    /**
     * 模型名称
     */
    @Schema(description = "模型名称", example = "gpt-4", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "模型名称不能为空")
    @Size(max = 100, message = "模型名称长度不能超过 100 个字符")
    private String modelName;

    /**
     * 模型供应商ID
     */
    @Schema(description = "模型供应商ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "模型供应商不能为空")
    private Long providerId;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 模型类型（chat/embedding）
     */
    @Schema(description = "模型类型（chat/embedding）", example = "chat", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "模型类型不能为空")
    private String modelType;

    /**
     * 是否支持联网
     */
    @Schema(description = "是否支持联网", example = "0")
    private Integer supportInternet;

    /**
     * 是否支持推理
     */
    @Schema(description = "是否支持推理", example = "0")
    private Integer supportReasoning;

    /**
     * 是否支持工具
     */
    @Schema(description = "是否支持工具", example = "0")
    private Integer supportTool;

    /**
     * 是否支持MCP
     */
    @Schema(description = "是否支持MCP", example = "0")
    private Integer supportMcp;

    /**
     * 是否支持视觉
     */
    @Schema(description = "是否支持视觉", example = "0")
    private Integer supportVision;

    /**
     * 价格（元/百万token）
     */
    @Schema(description = "价格（元/百万token）", example = "0.0000")
    private Double price;

    /**
     * 状态
     */
    @Schema(description = "状态（0:禁用，1:启用）", example = "1")
    private Integer status;
}
