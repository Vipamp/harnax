package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 同步技能响应 DTO
 *
 * @author vipamp
 * @since 2026-03-17
 */
@Data
@Schema(description = "同步技能响应对象")
public class SyncSkillResponse {

    /**
     * 技能名称
     */
    @Schema(description = "技能名称", example = "Java 编程助手")
    private String name;

    /**
     * 技能描述
     */
    @Schema(description = "技能描述", example = "提供 Java 编程相关的技能帮助")
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
     * 是否已存在（true: 已存在，false: 不存在）
     */
    @Schema(description = "是否已存在（true: 已存在，false: 不存在）", example = "false")
    private Boolean exists;
}
