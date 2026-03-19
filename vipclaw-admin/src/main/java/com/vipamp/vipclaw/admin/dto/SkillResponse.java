package com.vipamp.vipclaw.admin.dto;

import com.vipamp.vipclaw.admin.entity.Skill;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能响应 DTO
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Data
@Schema(description = "技能响应对象")
public class SkillResponse {

    /**
     * ID
     */
    @Schema(description = "ID", example = "1")
    private Long id;

    /**
     * 技能名称
     */
    @Schema(description = "技能名称", example = "test-skill")
    private String name;

    /**
     * 仓库ID
     */
    @Schema(description = "仓库ID", example = "1")
    private Long repositoryId;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称", example = "qoder-skills")
    private String repositoryName;

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
    @Schema(description = "创建时间", example = "2026-03-16 12:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2026-03-16 12:00:00")
    private LocalDateTime updateTime;

    /**
     * 从实体对象转换
     *
     * @param skill 技能实体
     * @return 技能响应对象
     */
    public static SkillResponse fromEntity(Skill skill) {
        if (skill == null) {
            return null;
        }
        SkillResponse response = new SkillResponse();
        response.setId(skill.getId());
        response.setName(skill.getName());
        response.setRepositoryId(skill.getRepositoryId());
        response.setDescription(skill.getDescription());
        response.setSkillmd(skill.getSkillmd());
        response.setResources(skill.getResources());
        response.setStatus(skill.getStatus());
        response.setIsPublic(skill.getIsPublic());
        response.setCreator(skill.getCreator());
        response.setCreateTime(skill.getCreateTime());
        response.setUpdateTime(skill.getUpdateTime());
        return response;
    }
}
