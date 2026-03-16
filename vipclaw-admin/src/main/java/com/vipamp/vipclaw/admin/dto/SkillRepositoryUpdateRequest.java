package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 技能仓库更新请求 DTO
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Data
@Schema(description = "技能仓库更新请求对象")
public class SkillRepositoryUpdateRequest {

    /**
     * ID
     */
    @Schema(description = "ID")
    private Long id;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称", example = "qoder-skills")
    @Size(min = 1, max = 100, message = "仓库名称长度必须在 1-100 之间")
    private String name;

    /**
     * 仓库地址
     */
    @Schema(description = "仓库地址", example = "https://github.com/example/skills")
    @Size(max = 500, message = "仓库地址长度不能超过 500")
    private String url;

    /**
     * 仓库描述
     */
    @Schema(description = "仓库描述")
    private String description;

    /**
     * 状态 (0:禁用 1:正常)
     **/
    @Schema(description = "状态 (0:禁用 1:正常)", example = "1")
    private Integer status;
}
