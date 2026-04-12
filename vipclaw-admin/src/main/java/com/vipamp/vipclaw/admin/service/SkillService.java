package com.vipamp.vipclaw.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.vipamp.vipclaw.admin.dto.SkillCreateRequest;
import com.vipamp.vipclaw.admin.dto.SkillResponse;
import com.vipamp.vipclaw.admin.dto.SkillUpdateRequest;
import com.vipamp.vipclaw.common.entity.Skill;
import jakarta.annotation.Nullable;

import java.util.List;

/**
 * 技能服务接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
public interface SkillService extends IService<Skill> {

    /**
     * 分页查询技能列表
     *
     * @param name         技能名称
     * @param repositoryId 仓库ID
     * @param status       状态筛选字段
     * @param current      当前页码
     * @param size         每页大小
     * @return 分页结果
     */
    Page<Skill> getSkillPage(@Nullable String name, @Nullable Long repositoryId, @Nullable Integer status, Integer current, Integer size);


    /**
     * 获取单个技能详情
     *
     * @param id 技能 ID
     * @return 技能实体
     */
    Skill getSkillById(Long id);

    /**
     * 创建技能
     *
     * @param request 技能创建请求对象
     * @return 创建结果
     */
    boolean createSkill(SkillCreateRequest request);

    /**
     * 更新技能
     *
     * @param id      技能 ID
     * @param request 技能更新请求对象
     * @return 更新结果
     */
    boolean updateSkill(Long id, SkillUpdateRequest request);

    /**
     * 切换技能启用状态
     *
     * @param id     技能 ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    boolean toggleSkillStatus(Long id, Integer status);

    /**
     * 删除技能
     *
     * @param id 技能 ID
     * @return 删除结果
     */
    boolean deleteSkill(Long id);

    /**
     * 根据技能名称查询技能
     *
     * @param repositoryId 仓库 ID
     * @param name         技能名称
     * @return 技能实体
     */
    Skill getByNameAndRepo(Long repositoryId, String name);

    /**
     * 批量保存技能（同步用）
     *
     * @param repositoryId 仓库 ID
     * @param skills       技能列表
     * @return 保存的技能数量
     */
    Integer batchSaveSkills(Long repositoryId, List<SkillResponse> skills);
}
