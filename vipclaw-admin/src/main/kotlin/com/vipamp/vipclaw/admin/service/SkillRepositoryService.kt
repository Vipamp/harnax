package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.SkillRepositoryCreateRequest
import com.vipamp.vipclaw.admin.dto.SkillRepositoryResponse
import com.vipamp.vipclaw.admin.dto.SkillRepositoryUpdateRequest
import com.vipamp.vipclaw.admin.dto.SyncSkillResponse
import com.vipamp.vipclaw.admin.entity.SkillRepository

/**
 * 技能仓库服务接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
interface SkillRepositoryService {

    /**
     * 分页查询技能仓库列表
     *
     * @param name     仓库名称
     * @param status   状态筛选字段
     * @param pageNum  当前页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    fun page(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<SkillRepository>

    /**
     * 获取所有启用的仓库列表
     *
     * @return 仓库列表
     */
    fun getActiveRepositories(): List<SkillRepository>

    /**
     * 获取单个技能仓库详情
     *
     * @param id 技能仓库 ID
     * @return 技能仓库实体
     */
    fun getSkillRepository(id: Long): SkillRepository?

    /**
     * 创建技能仓库
     *
     * @param request 技能仓库创建请求对象
     * @return 创建结果
     */
    fun createSkillRepository(request: SkillRepositoryCreateRequest): Boolean

    /**
     * 更新技能仓库
     *
     * @param id      技能仓库 ID
     * @param request 技能仓库更新请求对象
     * @return 更新结果
     */
    fun updateSkillRepository(id: Long, request: SkillRepositoryUpdateRequest): Boolean

    /**
     * 切换技能仓库启用状态
     *
     * @param id     技能仓库 ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun toggleSkillRepository(id: Long, status: Int): Boolean

    /**
     * 删除技能仓库
     *
     * @param id 技能仓库 ID
     * @return 删除结果
     */
    fun deleteSkillRepository(id: Long): Boolean

    /**
     * 根据仓库名称查询仓库
     *
     * @param name 仓库名称
     * @return 技能仓库实体
     */
    fun getByName(name: String): SkillRepository?

    /**
     * 获取远程技能列表（从 Git 仓库同步）
     *
     * @param repositoryId 技能仓库 ID
     * @return 远程技能列表
     */
    fun fetchRemoteSkills(repositoryId: Long): List<SyncSkillResponse>

    /**
     * 将技能仓库实体转换为技能仓库响应对象
     *
     * @param skillRepository 技能仓库实体
     * @return 技能仓库响应对象
     */
    fun convertToResponse(skillRepository: SkillRepository): SkillRepositoryResponse
}
