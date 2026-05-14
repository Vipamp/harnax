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
 * Skill service implementation
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
            "Paginated query for skill list, pageNum: {}, pageSize: {}, name: {}, repositoryId: {}, status: {}",
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
        log.info("Querying skill details, id: {}", id)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")
        return skill
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createSkill(request: SkillCreateRequest): Boolean {
        log.info("Creating skill, name: {}", request.name)

        // Check if skill name already exists (need to validate active field)
        val existSkill = getByNameAndRepo(request.repositoryId!!, request.name!!)
        if (existSkill != null) {
            throw BizException("Skill name already exists")
        }

        val skill = Skill()
        skill.name = request.name
        skill.repositoryId = request.repositoryId
        skill.description = request.description!!
        skill.skillmd = request.skillmd!!
        skill.resources = request.resources!!
        skill.status = request.status ?: 1 // Default enabled
        skill.active = 1 // Default active

        // Set tenant ID
        skill.tenantId = TenantContext.getTenantId() ?: 1

        // Set creator
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        skill.creator = currentUsername!!

        // Default not public
        if (skill.isPublic == null) {
            skill.isPublic = 0
        }

        val success = this.skillMapper.insert(skill) > 0
        log.info("Skill creation {}, skillId: {}", if (success) "successful" else "failed", skill.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateSkill(id: Long, request: SkillUpdateRequest): Boolean {
        log.info("Updating skill, id: {}", id)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")

        // If request contains skill name and it's different from current name, check if new name is already in use
        if (request.name != null && request.name != skill.name) {
            val existSkill = skillMapper.selectByNameAndRepo(request.name, skill.repositoryId)
            if (existSkill != null) {
                throw BizException("Skill name already exists")
            }
            skill.name = request.name
        }

        // Selectively update fields
        request.repositoryId?.let { skill.repositoryId = it }
        request.description?.let { skill.description = it }
        request.skillmd?.let { skill.skillmd = it }
        request.resources?.let { skill.resources = it }

        val success = this.skillMapper.updateById(skill) > 0
        log.info("Skill update {}, id: {}", if (success) "successful" else "failed", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleSkillStatus(id: Long, status: Int): Boolean {
        log.info("Toggling skill status, id: {}, status: {}", id, status)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")

        return skillMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkill(id: Long): Boolean {
        log.info("Deleting skill, id: {}", id)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")

        return skillMapper.deleteById(id) > 0
    }

    override fun getByNameAndRepo(repositoryId: Long, name: String): Skill? = skillMapper.selectByNameAndRepo(name, repositoryId)

    @Transactional(rollbackFor = [Exception::class])
    override fun batchSaveSkills(repositoryId: Long, skills: List<String>): Int {
        log.info("Batch saving skills, repositoryId: {}, count: {}", repositoryId, skills.size)

        if (skills.isEmpty()) {
            return 0
        }

        var savedCount = 0
        for (skillName in skills) {
            try {
                // Check if skill name already exists
                val existSkill = getByNameAndRepo(repositoryId, skillName)
                val skillRepository =
                    skillRepositoryService.getSkillRepository(repositoryId) ?: throw BizException("Skill repository not found")
                if (existSkill != null) {
                    // If skill already exists, keep as is, do not update
                    log.info("Skill already exists, skipping: {}", skillName)
                    savedCount++
                } else {
                    log.info("Skill does not exist, creating: {}", skillName)
                    loadSkillsFromGit(skillRepository.url, skillRepository.branch, localTmpDir, skillRepository.name)
                        .filter { it.name == skillName }
                        .map {
                            val skill = Skill()
                            skill.name = skillName
                            skill.repositoryId = repositoryId
                            skill.description = it.description
                            skill.skillmd = it.skillContent
                            skill.resources = objectMapper.writeValueAsString(it.resources)
                            skill.status = 1 // Default enabled
                            skill.active = 1 // Default active
                            skill
                        }
                        .forEach { this.skillMapper.insert(it) }
                    savedCount++
                }
            } catch (e: Exception) {
                log.error("Failed to save skill: {}", skillName, e)
                // Continue processing next skill, do not interrupt the entire process
            }
        }

        log.info("Batch saving skills completed, successfully saved: {} skills", savedCount)
        return savedCount
    }

    override fun convertToResponse(skill: Skill): SkillResponse {
        val repository = skillRepositoryService.getSkillRepository(skill.repositoryId)
        return SkillResponse.fromEntity(skill, repository)
    }
}
