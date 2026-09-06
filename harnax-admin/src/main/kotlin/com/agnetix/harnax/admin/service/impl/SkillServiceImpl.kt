package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillCreateRequest
import com.agnetix.harnax.admin.dto.SkillInstallResponse
import com.agnetix.harnax.admin.dto.SkillResponse
import com.agnetix.harnax.admin.dto.SkillUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.admin.skill.SkillInstaller
import com.agnetix.harnax.admin.skill.SkillSourceConfigs
import com.agnetix.harnax.admin.skill.SkillSourcePolicy
import com.agnetix.harnax.admin.skill.loader.SkillLoaderRegistry
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.CliSkillBindingMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val skillInstaller: SkillInstaller,
    @Value($$"${local.tmp-dir}") private val localTmpDir: String,
) : SkillService {

    private val log = LoggerFactory.getLogger(SkillServiceImpl::class.java)

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
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        // Built-in CLI skills belong to the seeding tenant but must be listable everywhere,
        // otherwise the CLI binding dialog comes up empty for other tenants
        val builtinRepositoryId = skillRepositoryService.getBuiltinRepository()?.id
        PageHelper.startPage<Skill>(safePageNum, safePageSize)
        return Page.fromPageInfo(
            skillMapper.selectSkillList(name, repositoryId, status, currentUsername, tenantId, builtinRepositoryId),
        )
    }

    override fun getSkill(id: Long): Skill? {
        log.info("Querying skill details, id: {}", id)

        // The row carries the full SKILL.md and every bundled resource, so reading it across
        // tenants would leak another tenant's skill content
        return skillMapper.selectById(id)?.also { requireReadable(it) }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createSkill(request: SkillCreateRequest): Boolean {
        log.info("Creating skill, name: {}", request.name)

        val name = SkillSourcePolicy.requireUsableText(request.name, "Skill name")
        val repositoryId = request.repositoryId
            ?: throw BizException("Repository ID cannot be empty")

        // Checked before any DB access, and with the same rule the toggle endpoint applies: an
        // out-of-range value fits the TINYINT column, but every delivery path tests `status == 1`,
        // so `status = 7` would store a skill that is neither switchable in the UI nor ever loaded
        val initialStatus = request.status ?: 1 // Default enabled
        SkillSourcePolicy.requireStatus(initialStatus)

        // Authorisation first: probing whether a name is taken must not be possible for a
        // repository the caller may not write to
        val repository = requireWritableRepo(repositoryId)

        val existSkill = getByNameAndRepo(repositoryId, name)
        if (existSkill != null) {
            throw BizException("Skill name already exists")
        }

        val skill = Skill()
        skill.name = name
        skill.repositoryId = repositoryId
        skill.description = request.description ?: ""
        skill.skillmd = request.skillmd ?: ""
        skill.resources = request.resources ?: ""
        skill.status = initialStatus
        skill.active = 1 // Default active
        // Follow the repository: a private skill inside a public repository is invisible in the
        // list even though the repository itself is shown
        skill.isPublic = repository.isPublic

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
        requireReadable(skill)

        // Builtin repository skills are read-only, and skills cannot be moved in/out of it
        requireWritableRepo(skill.repositoryId)
        val targetRepository = request.repositoryId?.let { requireWritableRepo(it) }

        // Resolve the final name and repository before checking uniqueness. A move that keeps the
        // name still collides with a skill already sitting in the destination, and leaving that to
        // `uk_skill_repo_active_name` answers with a raw SQL error instead of a usable message
        val targetName = request.name?.let { SkillSourcePolicy.requireUsableText(it, "Skill name") } ?: skill.name
        val targetRepositoryId = request.repositoryId ?: skill.repositoryId
        if (targetName != skill.name || targetRepositoryId != skill.repositoryId) {
            val existSkill = skillMapper.selectByNameAndRepo(targetName, targetRepositoryId)
            if (existSkill != null) {
                throw BizException("Skill name already exists")
            }
        }
        skill.name = targetName
        skill.repositoryId = targetRepositoryId
        // Visibility follows the repository, the rule createSkill and SkillInstaller already apply:
        // keeping the old value left a private skill invisible after a move into a public repository
        targetRepository?.let { skill.isPublic = it.isPublic }

        // Selectively update fields
        request.description?.let { skill.description = it }
        request.skillmd?.let { skill.skillmd = it }
        request.resources?.let { skill.resources = it }

        // status is not part of the updateById statement; it has a dedicated update. Leaving the
        // field unread here made `PUT /skills/update/{id}` answer 200 while the row kept its old
        // status, so `harnax skill update <id> --status 0` printed "updated successfully" and
        // changed nothing. `/skill-sources/{id}` already routes it to updateStatus; so does this
        request.status?.let { newStatus ->
            SkillSourcePolicy.requireStatus(newStatus)
            if (newStatus != skill.status) {
                skillMapper.updateStatus(id, newStatus)
                skill.status = newStatus
            }
        }

        val success = this.skillMapper.updateById(skill) > 0
        log.info("Skill update {}, id: {}", if (success) "successful" else "failed", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleSkillStatus(id: Long, status: Int): Boolean {
        log.info("Toggling skill status, id: {}, status: {}", id, status)
        SkillSourcePolicy.requireStatus(status)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")
        requireReadable(skill)
        requireWritableRepo(skill.repositoryId)

        return skillMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkill(id: Long): Boolean {
        log.info("Deleting skill, id: {}", id)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")
        requireReadable(skill)
        requireWritableRepo(skill.repositoryId)

        // Remove agent/cli references so no dangling bindings survive the delete
        agentSkillBindingMapper.deleteBySkillIds(listOf(id))
        cliSkillBindingMapper.deleteBySkillIds(listOf(id))

        return skillMapper.deleteById(id) > 0
    }

    /**
     * Resolves the repository a mutation targets and rejects it when the caller may not write.
     *
     * The builtin CLI skill repository is platform-managed: its skills are provisioned with the
     * platform and only reachable through CLI bindings. A missing repository is an error, not a
     * reason to let the write through — silently returning used to disable both checks at once.
     */
    private fun requireWritableRepo(repositoryId: Long): SkillRepository {
        val repository = skillRepositoryService.getSkillRepository(repositoryId)
            ?: throw BizException("Skill repository not found")
        if (BuiltinRepository.isBuiltin(repository.name)) {
            throw BizException("Repository '${BuiltinRepository.CLI_SKILLS}' is read-only, its skills cannot be created/modified/deleted")
        }
        val currentTenantId = TenantContext.getTenantId()
        if (currentTenantId != null && repository.tenantId != currentTenantId) {
            throw BizException("Skill repository belongs to another tenant")
        }
        return repository
    }

    /**
     * Tenant check for single-skill reads. A null context means an internal/system call.
     * Skills of the shared builtin repository are readable by every tenant.
     */
    private fun requireReadable(skill: Skill) {
        val currentTenantId = TenantContext.getTenantId() ?: return
        if (skill.tenantId == currentTenantId) return
        if (skill.repositoryId == skillRepositoryService.getBuiltinRepository()?.id) return
        throw BizException("Skill belongs to another tenant")
    }

    override fun getByNameAndRepo(repositoryId: Long, name: String): Skill? = skillMapper.selectByNameAndRepo(name, repositoryId)

    override fun batchSaveSkills(repositoryId: Long, skills: List<String>): Int = batchSaveSkillsDetailed(repositoryId, skills).savedCount

    /**
     * Selective sync: load the source once, then store exactly the skills the caller picked.
     *
     * Loading happens outside the transaction — a Git clone or `npm install` can take minutes and
     * must not hold row locks — and persistence is delegated to [SkillInstaller] so this path and
     * the `skill-sources` install share one upsert rule set and one failure report.
     */
    override fun batchSaveSkillsDetailed(repositoryId: Long, skills: List<String>): SkillInstallResponse {
        log.info("Batch saving skills, repositoryId: {}, count: {}", repositoryId, skills.size)
        // The endpoint takes an unbounded JSON list. Names the source does not contain are each
        // reported back, so without a ceiling one request can make the response carry tens of
        // thousands of failure entries
        if (skills.size > MAX_BATCH_SKILLS) {
            throw BizException("Too many skills selected, at most $MAX_BATCH_SKILLS per request")
        }
        val skillRepository = requireWritableRepo(repositoryId)
        // Answering before the loader is picked: a ZIP source keeps no archive, so the only honest
        // reply is "upload it again", not the config error the ZIP loader would raise
        SkillSourcePolicy.requireRefreshable(skillRepository)

        // Matched against the source by name, and `SkillInstaller` stores the trimmed one: a padded
        // entry from the CLI or an older frontend would otherwise come back as "not present in the
        // source anymore" while sitting right there in the source. Blank entries are dropped rather
        // than reported, a failure line naming an empty string tells the caller nothing
        val selected = skills.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (selected.isEmpty()) {
            return SkillInstallResponse()
        }

        val config = SkillSourceConfigs.parse(skillRepository)
        val loader = skillLoaderRegistry.getLoader(skillRepository.sourceType)
        loader.validateConfig(config)
        val base = Path.of(localTmpDir).also { Files.createDirectories(it) }
        val tmpDir = Files.createTempDirectory(base, "skill-sync-")

        val loaded = try {
            loader.loadSkills(config, tmpDir)
        } finally {
            try {
                tmpDir.toFile().deleteRecursively()
            } catch (e: Exception) {
                log.warn("Failed to cleanup tmp dir: {}", tmpDir, e)
            }
        }

        // `loaded.failures` covers the directories whose SKILL.md could not be parsed; without them
        // a selection of five skills that yields three stored rows answers as a plain success
        val install = skillInstaller.persist(skillRepository, loaded.skills, only = selected, loadFailures = loaded.failures)
        log.info("Batch saving skills completed for repository {}: {}", repositoryId, install.summary)
        return install
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

    private companion object {
        /** Same ceiling the paginated endpoints apply to `pageSize`. */
        const val MAX_BATCH_SKILLS = 1000
    }
}
