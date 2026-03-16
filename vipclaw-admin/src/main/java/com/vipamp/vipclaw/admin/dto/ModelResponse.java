package com.vipamp.vipclaw.admin.dto;

import com.vipamp.vipclaw.admin.entity.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型响应 DTO
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Data
@Schema(description = "模型响应对象")
public class ModelResponse {

    /**
     * ID
     */
    @Schema(description = "ID", example = "1")
    private Long id;

    /**
     * 名称
     */
    @Schema(description = "名称", example = "GPT-4")
    private String name;

    /**
     * 模型名称
     */
    @Schema(description = "模型名称", example = "gpt-4")
    private String modelName;

    /**
     * 模型供应商ID
     */
    @Schema(description = "模型供应商ID", example = "1")
    private Long providerId;

    /**
     * 模型供应商名称
     */
    @Schema(description = "模型供应商名称", example = "OpenAI")
    private String providerName;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 模型类型（chat/embedding）
     */
    @Schema(description = "模型类型（chat/embedding）", example = "chat")
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
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    private Integer status;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-03-13 12:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2026-03-13 12:00:00")
    private LocalDateTime updateTime;

    /**
     * 从实体对象转换
     *
     * @param model 模型实体
     * @return 模型响应对象
     */
    public static ModelResponse fromEntity(Model model) {
        if (model == null) {
            return null;
        }
        ModelResponse response = new ModelResponse();
        response.setId(model.getId());
        response.setName(model.getName());
        response.setModelName(model.getModelName());
        response.setProviderId(model.getProviderId());
        response.setDescription(model.getDescription());
        response.setModelType(model.getModelType());
        response.setSupportInternet(model.getSupportInternet());
        response.setSupportReasoning(model.getSupportReasoning());
        response.setSupportTool(model.getSupportTool());
        response.setSupportMcp(model.getSupportMcp());
        response.setSupportVision(model.getSupportVision());
        response.setPrice(model.getPrice());
        response.setStatus(model.getStatus());
        response.setCreateTime(model.getCreateTime());
        response.setUpdateTime(model.getUpdateTime());
        return response;
    }
}
