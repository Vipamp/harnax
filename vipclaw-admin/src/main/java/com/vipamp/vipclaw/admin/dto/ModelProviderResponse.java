package com.vipamp.vipclaw.admin.dto;

import com.vipamp.vipclaw.common.entity.ModelProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型服务商响应 DTO
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Data
@Schema(description = "模型服务商响应对象")
public class ModelProviderResponse {

    /**
     * ID
     */
    @Schema(description = "ID", example = "1")
    private Long id;

    /**
     * 服务商名称（dashscope/openai/ollama）
     */
    @Schema(description = "服务商名称（dashscope/openai/ollama）", example = "dashscope")
    private String name;

    /**
     * 显示名称
     */
    @Schema(description = "显示名称", example = "阿里云 DashScope")
    private String displayName;

    /**
     * API 密钥（脱敏显示）
     */
    @Schema(description = "API 密钥（脱敏显示）", example = "sk-****xxxx")
    private String apiKey;

    /**
     * API 地址
     */
    @Schema(description = "API 地址", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    private String baseUrl;

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    private Integer status;

    /**
     * 是否公开（0:否，1:是）
     */
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    private Integer isPublic;

    /**
     * 创建人
     */
    @Schema(description = "创建人", example = "admin")
    private String creator;

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
     * @param modelProvider 模型服务商实体
     * @return 模型服务商响应对象
     */
    public static ModelProviderResponse fromEntity(ModelProvider modelProvider) {
        if (modelProvider == null) {
            return null;
        }
        ModelProviderResponse response = new ModelProviderResponse();
        response.setId(modelProvider.getId());
        response.setName(modelProvider.getName());
        response.setDisplayName(modelProvider.getDisplayName());
        // API Key 脱敏处理
        response.setApiKey(maskApiKey(modelProvider.getApiKey()));
        response.setBaseUrl(modelProvider.getBaseUrl());
        response.setStatus(modelProvider.getStatus());
        response.setIsPublic(modelProvider.getIsPublic());
        response.setCreator(modelProvider.getCreator());
        response.setCreateTime(modelProvider.getCreateTime());
        response.setUpdateTime(modelProvider.getUpdateTime());
        return response;
    }

    /**
     * API Key 脱敏处理
     *
     * @param apiKey 原始 API Key
     * @return 脱敏后的 API Key
     */
    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return apiKey == null ? null : "********";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
