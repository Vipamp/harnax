package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 技能创建请求 DTO
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Data
@Schema(description = "技能创建请求对象")
public class SkillCreateRequest {

    /**
     * 技能名称
     */
    @Schema(description = "技能名称", example = "test-skill", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "技能名称不能为空")
    @Size(min = 1, max = 100, message = "技能名称长度必须在 1-100 之间")
    private String name;

    /**
     * 仓库ID
     */
    @Schema(description = "仓库ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long repositoryId;

    /**
     * 技能描述
     */
    @Schema(description = "技能描述")
    private String description;

    /**
     * skill.md 内容
     */
    @Schema(description = "skill.md 内容")
    private String skillmd;

    /**
     * 资源信息
     */
    @Schema(description = "资源信息")
    private String resources;

    /**
     * 状态 (0:禁用 1:正常)
     **/
    @Schema(description = "状态 (0:禁用 1:正常)", example = "1")
    private Integer status;
}
