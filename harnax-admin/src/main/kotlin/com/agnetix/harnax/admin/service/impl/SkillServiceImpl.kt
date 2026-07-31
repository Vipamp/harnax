package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillCreateRequest
import com.agnetix.harnax.admin.dto.SkillResponse
import com.agnetix.harnax.admin.dto.SkillUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.admin.skill.SkillSourceConfigs
import com.agnetix.harnax.admin.skill.loader.SkillLoaderRegistry
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.CliSkillBindingMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Skill service implementation
 */
@Service
class SkillServiceImpl(
    private val jwtUtil: JwtUtil,
    private val skillMapper: SkillMapper,
    private val skillRepositoryService: SkillRepositoryService,
    private val agentSkillBindingMapper: AgentSkillBindingMapper,
    private val cliSkillBindingMapper: CliSkillBindingMapper,
    private val skillLoaderRegistry: SkillLoaderRegistry,
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
        val tenantId = TenantContext.getTenantId() ?: 1
        PageHelper.startPage<Skill>(pageNum, pageSize)
        return Page.fromPageInfo(skillMapper.selectSkillList(name, repositoryId, status, currentUsername, tenantId))
    }

    override fun getSkill(id: Long): Skill? {
        log.info("Querying skill details, id: {}", id)

        return skillMapper.selectById(id)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createSkill(request: SkillCreateRequest): Boolean {
        log.info("Creating skill, name: {}", request.name)

        val name = request.name?.takeIf { it.isNotBlank() }
            ?: throw BizException("Skill name cannot be empty")
        val repositoryId = request.repositoryId
            ?: throw BizException("Repository ID cannot be empty")

        // Check if skill name already exists (need to validate active field)
        val existSkill = getByNameAndRepo(repositoryId, name)
        if (existSkill != null) {
            throw BizException("Skill name already exists")
        }
        requireNotBuiltinRepo(repositoryId)

        val skill = Skill()
        skill.name = name
        skill.repositoryId = repositoryId
        skill.description = request.description ?: ""
        skill.skillmd = request.skillmd ?: ""
        skill.resources = request.resources ?: ""
        skill.status = request.status ?: 1 // Default enabled
        skill.active = 1 // Default active
        skill.isPublic = 0

        // Set tenant ID
        skill.tenantId = TenantContext.getTenantId() ?: 1

        // Set creator
        skill.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""

        val success = this.skillMapper.insert(skill) > 0
        log.info("Skill creation {}, skillId: {}", if (success) "successful" else "failed", skill.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateSkill(id: Long, request: SkillUpdateRequest): Boolean {
        log.info("Updating skill, id: {}", id)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")

        // Builtin repository skills are read-only, and skills cannot be moved in/out of it
        requireNotBuiltinRepo(skill.repositoryId)
        request.repositoryId?.let { requireNotBuiltinRepo(it) }

        // If request contains skill name and it's different from current name, check if new name is already in use
        val targetRepositoryId = request.repositoryId ?: skill.repositoryId
        if (request.name != null && request.name != skill.name) {
            val existSkill = skillMapper.selectByNameAndRepo(request.name, targetRepositoryId)
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
        requireNotBuiltinRepo(skill.repositoryId)

        return skillMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkill(id: Long): Boolean {
        log.info("Deleting skill, id: {}", id)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")
        requireNotBuiltinRepo(skill.repositoryId)

        // Remove agent/cli references so no dangling bindings survive the delete
        agentSkillBindingMapper.deleteBySkillIds(listOf(id))
        cliSkillBindingMapper.deleteBySkillIds(listOf(id))

        return skillMapper.deleteById(id) > 0
    }

    /**
     * Rejects mutations targeting the builtin CLI skill repository — its skills are
     * provisioned with the platform and only reachable through CLI bindings.
     * Also rejects cross-tenant writes (null context = internal invocation, skipped).
     */
    private fun requireNotBuiltinRepo(repositoryId: Long) {
        val repository = skillRepositoryService.getSkillRepository(repositoryId) ?: return
        if (BuiltinRepository.isBuiltin(repository.name)) {
            throw BizException("Repository '${BuiltinRepository.CLI_SKILLS}' is read-only, its skills cannot be created/modified/deleted")
        }
        val currentTenantId = TenantContext.getTenantId()
        if (currentTenantId != null && repository.tenantId != currentTenantId) {
            throw BizException("Skill repository belongs to another tenant")
        }
    }

    override fun getByNameAndRepo(repositoryId: Long, name: String): Skill? = skillMapper.selectByNameAndRepo(name, repositoryId)

    @Transactional(rollbackFor = [Exception::class])
    override fun batchSaveSkills(repositoryId: Long, skills: List<String>): Int {
        log.info("Batch saving skills, repositoryId: {}, count: {}", repositoryId, skills.size)
        requireNotBuiltinRepo(repositoryId)

        if (skills.isEmpty()) {
            return 0
        }

        val skillRepository = skillRepositoryService.getSkillRepository(repositoryId)
            ?: throw BizException("Skill repository not found")

        // Load once via the source-type-aware loader (GIT/NPM/ZIP), then pick the requested ones
        val config = SkillSourceConfigs.parse(skillRepository)
        val loader = skillLoaderRegistry.getLoader(skillRepository.sourceType)
        val tmpDir = Files.createTempDirectory(Path.of(localTmpDir).also { Files.createDirectories(it) }, "skill-sync-")
        var savedCount = 0
        try {
            val loaded = loader.loadSkills(config, tmpDir).associateBy { it.name }
            for (skillName in skills) {
                val agentSkill = loaded[skillName]
                if (agentSkill == null) {
                    log.warn("Skill '{}' not found in source, skipping", skillName)
                    continue
                }

                val existing = getByNameAndRepo(repositoryId, skillName)
                if (existing != null) {
                    // Selected duplicates are overwritten, as promised by the sync dialog
                    existing.description = agentSkill.description ?: ""
                    existing.skillmd = agentSkill.skillContent ?: ""
                    existing.resources = objectMapper.writeValueAsString(agentSkill.resources ?: emptyMap<String, String>())
                    existing.version = skillRepository.version
                    skillMapper.updateById(existing)
                } else {
                    val skill = Skill()
                    skill.tenantId = TenantContext.getTenantId() ?: 1
                    skill.name = skillName
                    skill.repositoryId = repositoryId
                    skill.description = agentSkill.description ?: ""
                    skill.skillmd = agentSkill.skillContent ?: ""
                    skill.resources = objectMapper.writeValueAsString(agentSkill.resources ?: emptyMap<String, String>())
                    skill.version = skillRepository.version
                    skill.status = 1
                    skill.active = 1
                    skill.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: skillRepository.creator
                    skillMapper.insert(skill)
                }
                savedCount++
            }
        } finally {
            try {
                tmpDir.toFile().deleteRecursively()
            } catch (e: Exception) {
                log.warn("Failed to cleanup tmp dir: {}", tmpDir, e)
            }
        }

        log.info("Batch saving skills completed, successfully saved: {} skills", savedCount)
        return savedCount
    }

    override fun convertToResponse(skill: Skill): SkillResponse {
        val repository = skillRepositoryService.getSkillRepository(skill.repositoryId)
        return SkillResponse.fromEntity(skill, repository)
    }

    override fun convertToResponses(skills: List<Skill>): List<SkillResponse> {
        // Cache repository lookups so a page of skills triggers one query per distinct repository
        val repositories = skills.map { it.repositoryId }.distinct()
            .associateWith { skillRepositoryService.getSkillRepository(it) }
        return skills.map { SkillResponse.fromEntity(it, repositories[it.repositoryId]) }
    }
}
