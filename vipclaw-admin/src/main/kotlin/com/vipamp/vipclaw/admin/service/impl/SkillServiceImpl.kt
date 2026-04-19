package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.SkillCreateRequest
import com.vipamp.vipclaw.admin.dto.SkillResponse
import com.vipamp.vipclaw.admin.dto.SkillUpdateRequest
import com.vipamp.vipclaw.admin.entity.Skill
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SkillMapper
import com.vipamp.vipclaw.admin.service.SkillService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import com.vipamp.vipclaw.common.page.Page
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.min

/**
 * 技能服务实现类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Service
class SkillServiceImpl(
    private val jwtUtil: JwtUtil,
    private val skillMapper: SkillMapper
) : SkillService {

    private val log = LoggerFactory.getLogger(SkillServiceImpl::class.java)

    override fun getSkillPage(
        name: String?,
        repositoryId: Long?,
        status: Int?,
        current: Int,
        size: Int
    ): Page<Skill> {
        log.info(
            "分页查询技能列表，current: {}, size: {}, name: {}, repositoryId: {}, status: {}",
            current,
            size,
            name,
            repositoryId,
            status
        )

        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 使用 MyBatis 原生查询
        val allSkills = skillMapper.selectSkillList(name, repositoryId, status, currentUsername)

        // 手动分页
        val page = Page<Skill>(current.toLong(), size.toLong())
        val fromIndex = (current - 1) * size
        val toIndex = min(fromIndex + size, allSkills.size)

        page.records = if (fromIndex < allSkills.size) {
            allSkills.subList(fromIndex, toIndex)
        } else {
            emptyList()
        }
        page.total = allSkills.size.toLong()

        return page
    }

    override fun getSkillById(id: Long): Skill {
        log.info("查询技能详情，id: {}", id)

        val skill = skillMapper.selectActiveById(id)
            ?: throw BizException("技能不存在")
        return skill
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createSkill(request: SkillCreateRequest): Boolean {
        log.info("创建技能，name: {}", request.name)

        // 检查技能名称是否存在（需要同时校验 active 字段）
        val existSkill = getByNameAndRepo(request.repositoryId!!, request.name!!)
        if (existSkill != null) {
            throw BizException("技能名称已存在")
        }

        val skill = Skill()
        skill.name = request.name
        skill.repositoryId = request.repositoryId
        skill.description = request.description!!
        skill.skillmd = request.skillmd!!
        skill.resources = request.resources!!
        skill.status = request.status ?: 1 // 默认启用
        skill.active = 1  // 默认生效

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        skill.creator = currentUsername!!

        // 默认不公开
        if (skill.isPublic == null) {
            skill.isPublic = 0
        }

        val success = this.skillMapper.insert(skill) > 0
        log.info("技能创建{}，skillId: {}", if (success) "成功" else "失败", skill.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateSkill(id: Long, request: SkillUpdateRequest): Boolean {
        log.info("更新技能，id: {}", id)

        val skill = skillMapper.selectActiveById(id)
            ?: throw BizException("技能不存在")

        // 如果请求中包含技能名称且与当前技能名称不同，检查新技能名称是否已被使用
        if (request.name != null && request.name != skill.name) {
            val existSkill = skillMapper.selectByNameAndRepo(request.name, skill.repositoryId)
            if (existSkill != null) {
                throw BizException("技能名称已存在")
            }
            skill.name = request.name
        }

        // 选择性更新字段
        request.repositoryId?.let { skill.repositoryId = it }
        request.description?.let { skill.description = it }
        request.skillmd?.let { skill.skillmd = it }
        request.resources?.let { skill.resources = it }
        request.status?.let { skill.status = it }

        val success = this.skillMapper.updateById(skill) > 0
        log.info("技能更新{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleSkillStatus(id: Long, status: Int): Boolean {
        log.info("切换技能状态，id: {}, status: {}", id, status)

        val skill = skillMapper.selectActiveById(id)
            ?: throw BizException("技能不存在")

        return skillMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkill(id: Long): Boolean {
        log.info("删除技能，id: {}", id)

        val skill = skillMapper.selectActiveById(id)
            ?: throw BizException("技能不存在")

        return skillMapper.logicalDelete(id) > 0
    }

    override fun getByNameAndRepo(repositoryId: Long, name: String): Skill? {
        return skillMapper.selectByNameAndRepo(name, repositoryId)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun batchSaveSkills(repositoryId: Long, skills: List<SkillResponse>): Int {
        log.info("批量保存技能，repositoryId: {}, count: {}", repositoryId, skills?.size ?: 0)

        if (skills.isNullOrEmpty()) {
            return 0
        }

        var savedCount = 0
        for (skillData in skills) {
            try {
                // 检查技能名称是否存在
                val existSkill = getByNameAndRepo(repositoryId, skillData.name!!)
                if (existSkill != null) {
                    // 如果技能已存在，更新它，status 状态维持不变
                    log.info("技能已存在，执行更新：{}", skillData.name)
                    skillMapper.updateSkillFields(
                        existSkill.id,
                        skillData.description,
                        skillData.skillmd,
                        skillData.resources
                    )
                    savedCount++
                } else {
                    // 如果技能不存在，创建新技能
                    log.info("技能不存在，执行创建：{}", skillData.name)
                    val skill = Skill()
                    skill.name = skillData.name
                    skill.repositoryId = repositoryId
                    skill.description = skillData.description!!
                    skill.skillmd = skillData.skillmd!!
                    skill.resources = skillData.resources!!
                    skill.status = 1  // 默认启用
                    skill.active = 1  // 默认生效
                    this.skillMapper.insert(skill) > 0
                    savedCount++
                }
            } catch (e: Exception) {
                log.error("保存技能失败：{}", skillData.name, e)
                // 继续处理下一个技能，不中断整个流程
            }
        }

        log.info("批量保存技能完成，成功保存：{} 个", savedCount)
        return savedCount
    }
}
