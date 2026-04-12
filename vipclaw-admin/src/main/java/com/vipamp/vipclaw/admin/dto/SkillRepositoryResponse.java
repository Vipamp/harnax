package com.vipamp.vipclaw.admin.dto;

import com.vipamp.vipclaw.common.entity.SkillRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能仓库响应 DTO
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Data
@Schema(description = "技能仓库响应对象")
public class SkillRepositoryResponse {

    /**
     * ID
     */
    @Schema(description = "ID", example = "1")
    private Long id;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称", example = "qoder-skills")
    private String name;

    /**
     * 仓库地址
     */
    @Schema(description = "仓库地址", example = "https://github.com/example/skills")
    private String url;

    /**
     * 分支名称
     */
    @Schema(description = "分支名称", example = "main")
    private String branch;

    /**
     * 仓库描述
     */
    @Schema(description = "仓库描述")
    private String description;

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
     * @param repository 技能仓库实体
     * @return 技能仓库响应对象
     */
    public static SkillRepositoryResponse fromEntity(SkillRepository repository) {
        if (repository == null) {
            return null;
        }
        SkillRepositoryResponse response = new SkillRepositoryResponse();
        response.setId(repository.getId());
        response.setName(repository.getName());
        response.setUrl(repository.getUrl());
        response.setBranch(repository.getBranch());
        response.setDescription(repository.getDescription());
        response.setStatus(repository.getStatus());
        response.setIsPublic(repository.getIsPublic());
        response.setCreator(repository.getCreator());
        response.setCreateTime(repository.getCreateTime());
        response.setUpdateTime(repository.getUpdateTime());
        return response;
    }
}
