package com.vipamp.vipclaw.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.vipamp.vipclaw.admin.dto.SkillRepositoryCreateRequest;
import com.vipamp.vipclaw.admin.dto.SkillRepositoryUpdateRequest;
import com.vipamp.vipclaw.admin.dto.SyncSkillResponse;
import com.vipamp.vipclaw.admin.entity.SkillRepository;
import jakarta.annotation.Nullable;

import java.util.List;

/**
 * 技能仓库服务接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
public interface SkillRepositoryService extends IService<SkillRepository> {

    /**
     * 分页查询技能仓库列表
     *
     * @param name    仓库名称
     * @param status  状态筛选字段
     * @param current 当前页码
     * @param size    每页大小
     * @return 分页结果
     */
    Page<SkillRepository> getRepositoryPage(@Nullable String name, @Nullable Integer status, Integer current, Integer size);

    /**
     * 获取所有启用的仓库列表
     *
     * @return 仓库列表
     */
    List<SkillRepository> getActiveRepositories();

    /**
     * 获取单个技能仓库详情
     *
     * @param id 技能仓库 ID
     * @return 技能仓库实体
     */
    SkillRepository getRepositoryById(Long id);

    /**
     * 创建技能仓库
     *
     * @param request 技能仓库创建请求对象
     * @return 创建结果
     */
    boolean createRepository(SkillRepositoryCreateRequest request);

    /**
     * 更新技能仓库
     *
     * @param id      技能仓库 ID
     * @param request 技能仓库更新请求对象
     * @return 更新结果
     */
    boolean updateRepository(Long id, SkillRepositoryUpdateRequest request);

    /**
     * 切换技能仓库启用状态
     *
     * @param id     技能仓库 ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    boolean toggleRepositoryStatus(Long id, Integer status);

    /**
     * 删除技能仓库
     *
     * @param id 技能仓库 ID
     * @return 删除结果
     */
    boolean deleteRepository(Long id);

    /**
     * 根据仓库名称查询仓库
     *
     * @param name 仓库名称
     * @return 技能仓库实体
     */
    SkillRepository getByName(String name);

    /**
     * 获取远程技能列表（从 Git 仓库同步）
     *
     * @param repositoryId 技能仓库 ID
     * @return 远程技能列表
     */
    List<SyncSkillResponse> fetchRemoteSkills(Long repositoryId);
}
