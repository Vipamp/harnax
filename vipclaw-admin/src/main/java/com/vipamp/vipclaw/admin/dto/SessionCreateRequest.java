package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 会话创建请求对象
 *
 * @author vipamp
 * @since 2026-03-25
 */
@Data
@Schema(description = "会话创建请求对象")
public class SessionCreateRequest {

    /**
     * 会话名称
     */
    @Schema(description = "会话名称", example = "我的会话", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "会话名称不能为空")
    @Size(max = 100, message = "会话名称长度不能超过 100 个字符")
    private String title;

    /**
     * 会话描述
     */
    @Schema(description = "会话描述", example = "这是一个会话描述")
    private String sessionDescription;

    /**
     * 关联的智能体ID
     */
    @Schema(description = "关联的智能体ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "智能体ID不能为空")
    private Long agentId;
}
