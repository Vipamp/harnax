package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模型服务商创建请求 DTO
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Data
@Schema(description = "模型服务商创建请求对象")
public class ModelProviderCreateRequest {

    /**
     * 服务商名称（dashscope/openai/ollama）
     */
    @Schema(description = "服务商名称（dashscope/openai/ollama）", example = "dashscope", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "服务商名称不能为空")
    @Size(max = 50, message = "服务商名称长度不能超过 50 个字符")
    private String name;

    /**
     * 显示名称
     */
    @Schema(description = "显示名称", example = "阿里云 DashScope", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "显示名称不能为空")
    @Size(max = 100, message = "显示名称长度不能超过 100 个字符")
    private String displayName;

    /**
     * API 密钥
     */
    @Schema(description = "API 密钥", example = "sk-xxxxxxxxxxxxxxxx")
    private String apiKey;

    /**
     * API 地址
     */
    @Schema(description = "API 地址", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    private String baseUrl;

    /**
     * 状态（0:禁用，1:启用）
     */
    @Schema(description = "状态（0:禁用，1:启用）", example = "1")
    private Integer status;
}
