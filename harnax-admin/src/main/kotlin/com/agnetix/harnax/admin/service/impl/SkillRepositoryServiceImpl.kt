package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillRepositoryCreateRequest
import com.agnetix.harnax.admin.dto.SkillRepositoryResponse
import com.agnetix.harnax.admin.dto.SkillRepositoryUpdateRequest
import com.agnetix.harnax.admin.dto.SyncSkillResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.skill.SkillInstaller
import com.agnetix.harnax.admin.skill.SkillSourceConfigs
import com.agnetix.harnax.admin.skill.SkillSourcePolicy
import com.agnetix.harnax.admin.skill.loader.SkillLoaderRegistry
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Skill repository service implementation
 */
@Service
class SkillRepositoryServiceImpl(
    private val jwtUtil: JwtUtil,
    private val skillRepositoryMapper: SkillRepositoryMapper,
    private val skillMapper: SkillMapper,
    private val skillLoaderRegistry: SkillLoaderRegistry,
    private val skillInstaller: SkillInstaller,
    @Value($$"${local.tmp-dir}") private val localTmpDir: String?,
) : SkillRepositoryService {

    private val log = LoggerFactory.getLogger(SkillRepositoryServiceImpl::class.java)
    private val objectMapper = ObjectMapper()

    override fun page(
        name: String?,
        status: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<SkillRepository> {
        log.info(
            "Paginated query for skill repository list, pageNum: {}, pageSize: {}, name: {}, status: {}",
            pageNum,
            pageSize,
            name,
            status,
        )
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val tenantId = TenantContext.getTenantId() ?: 1
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<SkillRepository>(safePageNum, safePageSize)
        return Page.fromPageInfo(
            skillRepositoryMapper.selectRepositoryList(name, status, currentUsername, tenantId, BuiltinRepository.CLI_SKILLS),
        )
    }

    override fun getActiveRepositories(): List<SkillRepository> {
        val tenantId = TenantContext.getTenantId() ?: 1
        return skillRepositoryMapper.selectActiveRepositories(tenantId)
    }

    /**
     * Reads honour the tenant boundary, the way `fetchRemoteSkills` below and the `skill-sources`
     * API already do. Left unguarded, the legacy detail endpoint answered one tenant with another
     * tenant's repository, including a Git URL that may carry an embedded token. The shared builtin
     * repository is exempt because every tenant legitimately sees it; a null context means an
     * internal call.
     */
    override fun getSkillRepository(id: Long): SkillRepository? = skillRepositoryMapper.selectById(id)?.also {
        if (!BuiltinRepository.isBuiltin(it.name)) {
            requireSameTenant(it.tenantId)
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createSkillRepository(request: SkillRepositoryCreateRequest): Boolean {
        log.info("Creating skill repository, name: {}", request.name)

        val name = SkillSourcePolicy.requireUsableName(request.name, "Repository")

        // Same rule the toggle endpoint applies, checked before anything is written: an out-of-range
        // value fits the TINYINT column, but the repository list and every skill delivery path test
        // `status == 1`, so `status = 7` would create a repository that can never be switched on.
        // The request DTO already defaults this to 1 (enabled), so only the value needs checking
        val initialStatus = request.status
        SkillSourcePolicy.requireStatus(initialStatus)

        // Check if repository name already exists
        val tenantId = TenantContext.getTenantId() ?: 1
        val existRepository = skillRepositoryMapper.selectByName(name, tenantId)
        if (existRepository != null) {
            throw BizException("Repository name already exists")
        }

        // Validated before anything is written, the way the `skill-sources` API and this service's
        // own update do it. Storing whatever arrived deferred every URL problem to the first sync,
        // so the CLI could create a repository it could never fetch from and the real cause
        // ("Unsupported Git URL") surfaced far from the typo that produced it
        val config = SkillSourceConfigs.normalized(
            mapOf("url" to request.url, "branch" to request.branch.ifBlank { "main" }),
        )
        skillLoaderRegistry.getLoader("GIT").validateConfig(config)

        val repository = SkillRepository()
        repository.tenantId = tenantId
        repository.name = name
        // Both taken from the validated map so the legacy columns and the JSON config hold the same
        // text; a blank branch used to leave "" here and "main" there
        repository.url = config["url"] as? String ?: ""
        repository.branch = config["branch"] as? String ?: "main"
        repository.description = request.description
        repository.status = initialStatus
        repository.active = 1 // Default active
        // Private unless the caller says otherwise: the entity default (public) would expose a
        // repository created through the legacy API — and the CLI — to the whole tenant
        repository.isPublic = 0
        // This endpoint only accepts a Git URL, so derive the source descriptor from it and keep
        // the JSON config in sync with the legacy columns
        repository.sourceType = "GIT"
        repository.sourceConfig = objectMapper.writeValueAsString(config)

        // Set creator
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        repository.creator = currentUsername

        val success = this.skillRepositoryMapper.insert(repository) > 0
        log.info("Skill repository creation {}, repositoryId: {}", if (success) "successful" else "failed", repository.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateSkillRepository(id: Long, request: SkillRepositoryUpdateRequest): Boolean {
        log.info("Updating skill repository, id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill repository not found")
        requireNotBuiltin(repository)

        // If request contains repository name and it's different from current name, check if new name is already in use
        request.name?.let { incoming ->
            val renamed = SkillSourcePolicy.requireUsableName(incoming, "Repository")
            if (renamed != repository.name) {
                val existRepository = skillRepositoryMapper.selectByName(renamed, repository.tenantId)
                if (existRepository != null) {
                    throw BizException("Repository name already exists")
                }
                repository.name = renamed
            }
        }

        // Selectively update fields
        request.url?.let { repository.url = it.trim() }
        request.branch?.let { repository.branch = it.trim() }
        request.description?.let { repository.description = it }

        // status is not part of the updateById statement; it has a dedicated update. Without this the
        // endpoint answered 200 while the row kept its old status, so `harnax skill-repo update <id>
        // --status 0` printed "updated successfully" and changed nothing
        request.status?.let { newStatus ->
            SkillSourcePolicy.requireStatus(newStatus)
            if (newStatus != repository.status) {
                skillRepositoryMapper.updateStatus(id, newStatus)
                repository.status = newStatus
            }
        }

        // `fetchRemoteSkills` reads `sourceConfig` before the legacy columns, so writing only
        // url/branch left the edit looking successful while the next sync still cloned the
        // previous address. The new `skill-sources` API keeps both in sync; this one has to as well.
        if (repository.sourceType == "GIT" && (request.url != null || request.branch != null)) {
            val merged = SkillSourceConfigs.normalized(
                SkillSourceConfigs.parse(repository).toMutableMap().apply {
                    put("url", repository.url)
                    put("branch", repository.branch.ifBlank { "main" })
                },
            )
            skillLoaderRegistry.getLoader(repository.sourceType).validateConfig(merged)
            repository.sourceConfig = objectMapper.writeValueAsString(merged)
        }

        val success = this.skillRepositoryMapper.updateById(repository) > 0
        log.info("Skill repository update {}, id: {}", if (success) "successful" else "failed", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleSkillRepository(id: Long, status: Int): Boolean {
        log.info("Toggling skill repository status, id: {}, status: {}", id, status)
        SkillSourcePolicy.requireStatus(status)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill repository not found")
        requireNotBuiltin(repository)

        return skillRepositoryMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkillRepository(id: Long): Boolean {
        log.info("Deleting skill repository, id: {}", id)
        val repository = skillRepositoryMapper.selectById(id) ?: return false
        requireNotBuiltin(repository)
        // Cascade: leaving the skills behind would keep them reachable through agent bindings
        // while their repository is gone
        skillInstaller.deleteWithSkills(repository)
        return true
    }

    private fun requireNotBuiltin(repository: SkillRepository) {
        if (repository.name == BuiltinRepository.CLI_SKILLS) {
            throw BizException("Builtin repository '${BuiltinRepository.CLI_SKILLS}' is read-only")
        }
        requireSameTenant(repository.tenantId)
    }

    /**
     * Write operations must target the caller's own tenant.
     * A null context (internal/system invocation) skips the check.
     */
    private fun requireSameTenant(resourceTenantId: Long) {
        val currentTenantId = TenantContext.getTenantId() ?: return
        if (resourceTenantId != currentTenantId) {
            throw BizException("Skill repository belongs to another tenant")
        }
    }

    override fun getByName(name: String): SkillRepository? {
        val tenantId = TenantContext.getTenantId() ?: 1
        return skillRepositoryMapper.selectByName(name, tenantId)
    }

    override fun getBuiltinRepository(): SkillRepository? = skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)

    override fun fetchRemoteSkills(repositoryId: Long): List<SyncSkillResponse> {
        log.info("Fetching remote skill list, repositoryId: {}", repositoryId)
        // The tenant rule every other read here applies, builtin included: `getSkillRepository`
        // already exempts the shared repository, so its "nothing to fetch" answer is not masked by
        // a tenant error
        val repository = getSkillRepository(repositoryId)
            ?: throw BizException("Skill repository not found")
        SkillSourcePolicy.requireRefreshable(repository)

        val config = SkillSourceConfigs.parse(repository)
        val loader = skillLoaderRegistry.getLoader(repository.sourceType)
        loader.validateConfig(config)
        val base = Path.of(localTmpDir ?: System.getProperty("java.io.tmpdir"))
        Files.createDirectories(base)
        val tmpDir = Files.createTempDirectory(base, "skill-fetch-")
        val existingNames = skillMapper.selectByRepositoryId(repositoryId).map { it.name }.toSet()

        return try {
            val loaded = loader.loadSkills(config, tmpDir)
            // Same split as the `skill-sources` preview: this endpoint answers with a plain list, so
            // the directories that could not be parsed are logged here and reach the operator through
            // the `failed` report of the sync that follows, instead of widening a contract three
            // clients already depend on
            if (loaded.failures.isNotEmpty()) {
                log.warn(
                    "Repository {} preview could not read {} skill(s): {}",
                    repositoryId,
                    loaded.failures.size,
                    loaded.failures.joinToString(", ") { "${it.name} (${it.reason})" },
                )
            }
            loaded.skills.map {
                // Reported the way it will be stored: `SkillInstaller` trims the name, so echoing the
                // raw value made a padded name look new (`exists` compares against stored rows) and
                // then fail to resolve when the operator sent the selection back
                val name = it.name?.trim().orEmpty()
                SyncSkillResponse(
                    name = name,
                    description = it.description,
                    skillmd = it.skillContent,
                    resources = it.resources,
                    exists = existingNames.contains(name),
                )
            }
        } finally {
            try {
                tmpDir.toFile().deleteRecursively()
            } catch (e: Exception) {
                log.warn("Failed to cleanup tmp dir: {}", tmpDir, e)
            }
        }
    }

    override fun convertToResponse(skillRepository: SkillRepository): SkillRepositoryResponse = SkillRepositoryResponse.fromEntity(skillRepository)
}
