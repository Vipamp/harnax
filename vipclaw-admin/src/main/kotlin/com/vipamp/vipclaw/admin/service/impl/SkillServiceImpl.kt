package com.vipamp.vipclaw.admin.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.context.TenantContext
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.SkillCreateRequest
import com.vipamp.vipclaw.admin.dto.SkillResponse
import com.vipamp.vipclaw.admin.dto.SkillUpdateRequest
import com.vipamp.vipclaw.admin.entity.Skill
import com.vipamp.vipclaw.admin.entity.SysJob
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SkillMapper
import com.vipamp.vipclaw.admin.service.SkillRepositoryService
import com.vipamp.vipclaw.admin.service.SkillService
import com.vipamp.vipclaw.admin.util.GitSkillLoader.loadSkillsFromGit
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 技能服务实现类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Service
class SkillServiceImpl(
    private val jwtUtil: JwtUtil,
    private val skillMapper: SkillMapper,
    private val skillRepositoryService: SkillRepositoryService,
    @Value($$"${local.tmp-dir}") private val localTmpDir: String,
) : SkillService {

    private val log = LoggerFactory.getLogger(SkillServiceImpl::class.java)
    private val objectMapper = ObjectMapper()

    override fun page(
        name: String?,
        repositoryId: Long?,
        status: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<Skill> {
        log.info(
            "分页查询技能列表，pageNum: {}, pageSize: {}, name: {}, repositoryId: {}, status: {}",
            pageNum,
            pageSize,
            name,
            repositoryId,
            status,
        )
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        PageHelper.startPage<SysJob>(pageNum, pageSize)
        return Page.fromPageInfo(skillMapper.selectSkillList(name, repositoryId, status, currentUsername))
    }

    override fun getSkill(id: Long): Skill {
        log.info("查询技能详情，id: {}", id)

        val skill = skillMapper.selectById(id)
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
        skill.active = 1 // 默认生效

        // 设置租户ID
        skill.tenantId = TenantContext.getTenantId() ?: 1

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

        val skill = skillMapper.selectById(id)
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

        val success = this.skillMapper.updateById(skill) > 0
        log.info("技能更新{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleSkillStatus(id: Long, status: Int): Boolean {
        log.info("切换技能状态，id: {}, status: {}", id, status)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("技能不存在")

        return skillMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkill(id: Long): Boolean {
        log.info("删除技能，id: {}", id)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("技能不存在")

        return skillMapper.deleteById(id) > 0
    }

    override fun getByNameAndRepo(repositoryId: Long, name: String): Skill? = skillMapper.selectByNameAndRepo(name, repositoryId)

    @Transactional(rollbackFor = [Exception::class])
    override fun batchSaveSkills(repositoryId: Long, skills: List<String>): Int {
        log.info("批量保存技能,repositoryId: {}, count: {}", repositoryId, skills.size)

        if (skills.isEmpty()) {
            return 0
        }

        var savedCount = 0
        for (skillName in skills) {
            try {
                // 检查技能名称是否存在
                val existSkill = getByNameAndRepo(repositoryId, skillName)
                val skillRepository =
                    skillRepositoryService.getSkillRepository(repositoryId) ?: throw BizException("技能仓库不存在")
                if (existSkill != null) {
                    // 如果技能已存在,保持现状,不更新
                    log.info("技能已存在,跳过:{}", skillName)
                    savedCount++
                } else {
                    log.info("技能不存在,执行创建:{}", skillName)
                    loadSkillsFromGit(skillRepository.url, skillRepository.branch, localTmpDir, skillRepository.name)
                        .filter { it.name == skillName }
                        .map {
                            val skill = Skill()
                            skill.name = skillName
                            skill.repositoryId = repositoryId
                            skill.description = it.description
                            skill.skillmd = it.skillContent
                            skill.resources = objectMapper.writeValueAsString(it.resources)
                            skill.status = 1 // 默认启用
                            skill.active = 1 // 默认生效
                            skill
                        }
                        .forEach { this.skillMapper.insert(it) }
                    savedCount++
                }
            } catch (e: Exception) {
                log.error("保存技能失败:{}", skillName, e)
                // 继续处理下一个技能,不中断整个流程
            }
        }

        log.info("批量保存技能完成,成功保存:{} 个", savedCount)
        return savedCount
    }

    override fun convertToResponse(skill: Skill): SkillResponse {
        val repository = skillRepositoryService.getSkillRepository(skill.repositoryId)
        return SkillResponse.fromEntity(skill, repository)
    }
}
