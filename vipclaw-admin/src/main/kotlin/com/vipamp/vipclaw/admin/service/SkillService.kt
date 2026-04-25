package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.SkillCreateRequest
import com.vipamp.vipclaw.admin.dto.SkillResponse
import com.vipamp.vipclaw.admin.dto.SkillUpdateRequest
import com.vipamp.vipclaw.admin.entity.Skill
import com.vipamp.vipclaw.common.page.Page

/**
 * 技能服务接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
interface SkillService {

    /**
     * 分页查询技能列表
     *
     * @param name         技能名称
     * @param repositoryId 仓库ID
     * @param status       状态筛选字段
     * @param pageNum      当前页码
     * @param pageSize     每页大小
     * @return 分页结果
     */
    fun page(name: String?, repositoryId: Long?, status: Int?, pageNum: Int, pageSize: Int): Page<Skill>

    /**
     * 获取单个技能详情
     *
     * @param id 技能 ID
     * @return 技能实体
     */
    fun getSkill(id: Long): Skill?

    /**
     * 创建技能
     *
     * @param request 技能创建请求对象
     * @return 创建结果
     */
    fun createSkill(request: SkillCreateRequest): Boolean

    /**
     * 更新技能
     *
     * @param id      技能 ID
     * @param request 技能更新请求对象
     * @return 更新结果
     */
    fun updateSkill(id: Long, request: SkillUpdateRequest): Boolean

    /**
     * 切换技能启用状态
     *
     * @param id     技能 ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun toggleSkillStatus(id: Long, status: Int): Boolean

    /**
     * 删除技能
     *
     * @param id 技能 ID
     * @return 删除结果
     */
    fun deleteSkill(id: Long): Boolean

    /**
     * 根据技能名称查询技能
     *
     * @param repositoryId 仓库 ID
     * @param name         技能名称
     * @return 技能实体
     */
    fun getByNameAndRepo(repositoryId: Long, name: String): Skill?

    /**
     * 批量保存技能（同步用）
     *
     * @param repositoryId 仓库 ID
     * @param skills       技能列表
     * @return 保存的技能数量
     */
    fun batchSaveSkills(repositoryId: Long, skills: List<SkillResponse>): Int

    fun convertToResponse(skill: Skill): SkillResponse
}
