package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillSourceService
import com.agnetix.harnax.admin.skill.SkillInstaller
import com.agnetix.harnax.admin.skill.SkillSourceConfigs
import com.agnetix.harnax.admin.skill.SkillSourcePolicy
import com.agnetix.harnax.admin.skill.loader.SkillLoader
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

@Service
class SkillSourceServiceImpl(
    private val jwtUtil: JwtUtil,
    private val skillRepositoryMapper: SkillRepositoryMapper,
    private val skillMapper: SkillMapper,
    private val skillLoaderRegistry: SkillLoaderRegistry,
    private val skillInstaller: SkillInstaller,
    @Value("\${local.tmp-dir}") private val localTmpDir: String?,
) : SkillSourceService {

    private val log = LoggerFactory.getLogger(SkillSourceServiceImpl::class.java)
    private val objectMapper = ObjectMapper()

    override fun page(
        name: String?,
        sourceType: String?,
        status: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<SkillSourceResponse> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val tenantId = TenantContext.getTenantId() ?: 1
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<SkillRepository>(safePageNum, safePageSize)
        val entityPage = Page.fromPageInfo(
            skillRepositoryMapper.selectRepositoryList(
                name,
                status,
                currentUsername,
                tenantId,
                BuiltinRepository.CLI_SKILLS,
                // The endpoint advertises this filter, so it has to reach the query: dropping it
                // silently answered an unfiltered list to anyone asking for one source type
                sourceType?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
        return entityPage.mapRecords { convertToResponse(it) }
    }

    override fun getSkillSource(id: Long): SkillRepository? = skillRepositoryMapper.selectById(id)?.also { requireReadable(it) }

    /**
     * `selectActiveRepositories` already folds in the shared builtin repository, so the caller does
     * not have to add it. A null tenant means an internal call, where the default tenant is the
     * only sensible scope, matching the legacy endpoint.
     */
    override fun listActive(): List<SkillRepository> = skillRepositoryMapper.selectActiveRepositories(TenantContext.getTenantId() ?: 1)

    /**
     * Creates the source and installs its skills.
     *
     * The slow part (clone / npm install / unzip) runs before anything is written; only the
     * persistence step is transactional, via [SkillInstaller].
     */
    override fun createSkillSource(request: SkillSourceCreateRequest): SkillSourceInstallResponse {
        log.info("Creating skill source, name: {}, type: {}", request.name, request.sourceType)

        val tenantId = TenantContext.getTenantId() ?: 1
        val name = SkillSourcePolicy.requireUsableName(request.name, "Source")
        // Checked before the name probe and long before the fetch: an out-of-range value fits the
        // TINYINT column, but every reader tests `status == 1`, so `status = 7` would create a
        // source that can never be switched on
        val initialStatus = request.status ?: 1
        SkillSourcePolicy.requireStatus(initialStatus)
        if (skillRepositoryMapper.selectByName(name, tenantId) != null) {
            throw BizException("Source name already exists")
        }

        // A ZIP source *is* the archive, so it can only be created by uploading one. Letting it
        // through answered with the loader's "ZIP source config requires 'zipPath'" and never said
        // which endpoint to use instead
        if (request.sourceType == "ZIP") {
            throw BizException("ZIP sources are created by uploading an archive to POST /api/admin/skill-sources/upload")
        }

        // Normalised once, on the way in: the same map feeds validation, the stored JSON and the
        // legacy url/branch columns, so the three cannot disagree about a padded value
        val config = SkillSourceConfigs.normalized(buildConfigMap(request.sourceType, request.sourceConfig, request.url, request.branch))
        val loader = skillLoaderRegistry.getLoader(request.sourceType)
        loader.validateConfig(config)

        val loaded = loadFromSource(loader, config)

        val repository = SkillRepository().apply {
            this.tenantId = tenantId
            this.name = name
            this.sourceType = request.sourceType
            this.sourceConfig = objectMapper.writeValueAsString(persistableConfig(request.sourceType, config))
            this.version = request.version ?: ""
            this.description = request.description
            this.status = initialStatus
            this.isPublic = request.isPublic ?: 0
            this.active = 1
            this.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""
            if (request.sourceType == "GIT") {
                // Legacy columns mirror the JSON config so older readers keep working
                this.url = config["url"] as? String ?: ""
                this.branch = config["branch"] as? String ?: "main"
            }
        }

        val install = skillInstaller.createWithSkills(repository, loaded.skills, loaded.failures) {
            skillRepositoryMapper.selectByName(name, tenantId) != null
        }

        log.info("Skill source created, id: {}, install: {}", repository.id, install.summary)
        return SkillSourceInstallResponse(convertToResponse(repository), install)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateSkillSource(id: Long, request: SkillSourceUpdateRequest): Boolean {
        log.info("Updating skill source, id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")
        requireWritable(repository)

        request.name?.let { incoming ->
            // Trimming matters here too: an untrimmed rename stores a name that no longer matches
            // the value the caller typed, and the unique index compares the stored text
            val renamed = SkillSourcePolicy.requireUsableName(incoming, "Source")
            if (renamed != repository.name) {
                val existing = skillRepositoryMapper.selectByName(renamed, repository.tenantId)
                if (existing != null) {
                    throw BizException("Source name already exists")
                }
                repository.name = renamed
            }
        }

        request.description?.let { repository.description = it }
        request.version?.let { repository.version = it }
        request.isPublic?.let { visibility ->
            repository.isPublic = visibility
            // Visibility of already-installed skills follows the repository, otherwise a public
            // source keeps private skills and they never show up in the skill list
            skillMapper.selectByRepositoryId(repository.id).forEach { skill ->
                if (skill.isPublic != visibility) {
                    skill.isPublic = visibility
                    skillMapper.updateById(skill)
                }
            }
        }

        // status is not part of the updateById statement; it has a dedicated update
        request.status?.let { newStatus ->
            SkillSourcePolicy.requireStatus(newStatus)
            if (newStatus != repository.status) {
                skillRepositoryMapper.updateStatus(id, newStatus)
                repository.status = newStatus
            }
        }

        applySourceConfigChange(repository, request)

        return skillRepositoryMapper.updateById(repository) > 0
    }

    /**
     * Keeps `sourceConfig` and the legacy `url` / `branch` columns in sync.
     *
     * Updating the configuration does NOT re-install anything — the caller gets the new settings
     * stored and must trigger `POST /{id}/install` to refresh the persisted skill content.
     */
    private fun applySourceConfigChange(repository: SkillRepository, request: SkillSourceUpdateRequest) {
        val incoming = request.sourceConfig?.takeIf { it.isNotEmpty() }
        val current = SkillSourceConfigs.parse(repository)

        if (incoming == null) {
            request.url?.let { repository.url = it.trim() }
            request.branch?.let { repository.branch = it.trim() }
            // Mirror the legacy columns into the JSON config so both stay readable
            if (repository.sourceType == "GIT" && (request.url != null || request.branch != null)) {
                val merged = SkillSourceConfigs.normalized(
                    current.toMutableMap().apply {
                        put("url", repository.url)
                        put("branch", repository.branch)
                    },
                )
                skillLoaderRegistry.getLoader(repository.sourceType).validateConfig(merged)
                repository.sourceConfig = objectMapper.writeValueAsString(merged)
            }
            return
        }

        if (repository.sourceType == "ZIP") {
            // A ZIP source keeps no archive, so its configuration is fixed at upload time
            log.info("Ignoring sourceConfig update for ZIP source {}", repository.id)
            return
        }

        // Preserve internal keys that are filtered out of API responses
        val merged = SkillSourceConfigs.normalized(incoming).toMutableMap()
        for (key in INTERNAL_CONFIG_KEYS) {
            if (!merged.containsKey(key)) {
                current[key]?.let { merged[key] = it }
            }
        }
        skillLoaderRegistry.getLoader(repository.sourceType).validateConfig(merged)
        repository.sourceConfig = objectMapper.writeValueAsString(merged)
        if (repository.sourceType == "GIT") {
            repository.url = merged["url"] as? String ?: repository.url
            repository.branch = merged["branch"] as? String ?: repository.branch
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkillSource(id: Long): Boolean {
        log.info("Deleting skill source, id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")
        requireWritable(repository)

        skillInstaller.deleteWithSkills(repository)
        return true
    }

    /**
     * The builtin CLI skill repository is platform-managed and read-only;
     * other repositories may only be written by their own tenant.
     */
    private fun requireWritable(repository: SkillRepository) {
        if (BuiltinRepository.isBuiltin(repository.name)) {
            throw BizException("Builtin repository '${BuiltinRepository.CLI_SKILLS}' is read-only")
        }
        requireReadable(repository)
    }

    /**
     * Reads honour the tenant boundary too: `fetchSkills` would otherwise let one tenant make the
     * admin service clone an arbitrary URL configured by another. The shared builtin repository is
     * exempt because every tenant legitimately sees it. A null context means an internal call.
     */
    private fun requireReadable(repository: SkillRepository) {
        val currentTenantId = TenantContext.getTenantId() ?: return
        if (BuiltinRepository.isBuiltin(repository.name)) return
        if (repository.tenantId != currentTenantId) {
            throw BizException("Skill repository belongs to another tenant")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleStatus(id: Long, status: Int): Boolean {
        log.info("Toggling skill source status, id: {}, status: {}", id, status)
        SkillSourcePolicy.requireStatus(status)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")
        requireWritable(repository)

        return skillRepositoryMapper.updateStatus(id, status) > 0
    }

    override fun fetchSkills(id: Long): List<SyncSkillResponse> {
        log.info("Fetching skills from source, id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")
        requireReadable(repository)
        SkillSourcePolicy.requireRefreshable(repository)

        val config = SkillSourceConfigs.parse(repository)
        val loader = skillLoaderRegistry.getLoader(repository.sourceType)
        // Same gate as installSkills: a stored URL outside the transport whitelist must be named
        // here rather than surfacing as an opaque clone failure further down
        loader.validateConfig(config)
        val existingNames = skillMapper.selectByRepositoryId(id).map { it.name }.toSet()

        val loaded = loadFromSource(loader, config)
        // The preview answers with a plain list of what can be installed, so a directory that could
        // not be parsed is logged here and reported to the operator by the sync that follows: it is
        // the install response that carries a `failed` list, and widening this endpoint's contract
        // would mean changing three clients at once
        if (loaded.failures.isNotEmpty()) {
            log.warn(
                "Source {} preview could not read {} skill(s): {}",
                id,
                loaded.failures.size,
                loaded.failures.joinToString(", ") { "${it.name} (${it.reason})" },
            )
        }

        return loaded.skills.map { skill ->
            // Same normalisation as the legacy preview endpoint: `SkillInstaller` stores the trimmed
            // name, so the preview has to show it, otherwise `exists` is wrong for a padded name and
            // the selection the caller sends back cannot be resolved
            val name = skill.name?.trim().orEmpty()
            SyncSkillResponse(
                name = name,
                description = skill.description,
                skillmd = skill.skillContent,
                resources = skill.resources,
                exists = existingNames.contains(name),
            )
        }
    }

    override fun uploadAndInstall(zipPath: String, originalFilename: String, name: String): SkillSourceInstallResponse {
        log.info("Installing skill from ZIP upload: {}", originalFilename)

        val tenantId = TenantContext.getTenantId() ?: 1
        val sourceName = SkillSourcePolicy.requireUsableName(name, "Source")
        if (skillRepositoryMapper.selectByName(sourceName, tenantId) != null) {
            throw BizException("Source name already exists")
        }

        // The uploaded archive is read exactly once, right here: the caller deletes the temp file
        // afterwards, so nothing pointing at it may be persisted as the source configuration
        val loaded = loadFromSource(skillLoaderRegistry.getLoader("ZIP"), mapOf("zipPath" to zipPath))

        val repository = SkillRepository().apply {
            this.tenantId = tenantId
            this.name = sourceName
            this.sourceType = "ZIP"
            this.sourceConfig = objectMapper.writeValueAsString(mapOf("originalFilename" to originalFilename))
            this.description = "Uploaded ZIP: $originalFilename"
            this.status = 1
            this.isPublic = 0
            this.active = 1
            this.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""
        }

        val install = skillInstaller.createWithSkills(repository, loaded.skills, loaded.failures) {
            skillRepositoryMapper.selectByName(sourceName, tenantId) != null
        }

        log.info("ZIP skill source created, id: {}, install: {}", repository.id, install.summary)
        return SkillSourceInstallResponse(convertToResponse(repository), install)
    }

    override fun installSkills(id: Long): SkillInstallResponse {
        log.info("Re-installing skills from source, id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")
        requireWritable(repository)
        SkillSourcePolicy.requireRefreshable(repository)

        val config = SkillSourceConfigs.parse(repository)
        val loader = skillLoaderRegistry.getLoader(repository.sourceType)
        loader.validateConfig(config)

        val loaded = loadFromSource(loader, config)
        val install = skillInstaller.persist(repository, loaded.skills, loadFailures = loaded.failures)

        log.info("Re-install finished for source {}, result: {}", id, install.summary)
        return install
    }

    override fun convertToResponse(entity: SkillRepository): SkillSourceResponse = SkillSourceResponse.fromEntity(entity)

    /**
     * Runs the source loader outside any transaction and always cleans the scratch directory.
     *
     * The result carries both the skills and the directories the loader could not read; handing the
     * failures on is what keeps a broken `SKILL.md` from disappearing without a trace.
     */
    private fun loadFromSource(loader: SkillLoader, config: Map<String, Any>) = withTempDir { loader.loadSkills(config, it) }

    private fun <T> withTempDir(block: (Path) -> T): T {
        val tmpDir = getTmpDir()
        return try {
            block(tmpDir)
        } finally {
            cleanupTmpDir(tmpDir)
        }
    }

    /** Drops transport-only keys (the uploaded archive path) from what gets stored. */
    private fun persistableConfig(sourceType: String, config: Map<String, Any>): Map<String, Any> = if (sourceType == "ZIP") config.filterKeys { it != "zipPath" } else config

    private fun buildConfigMap(
        sourceType: String,
        sourceConfig: Map<String, Any>,
        url: String,
        branch: String,
    ): Map<String, Any> {
        if (sourceConfig.isNotEmpty()) return sourceConfig

        return when (sourceType) {
            "GIT" -> mapOf("url" to url, "branch" to (branch.ifBlank { "main" }))
            else -> sourceConfig
        }
    }

    private fun getTmpDir(): Path {
        val base = localTmpDir ?: System.getProperty("java.io.tmpdir")
        val basePath = Path.of(base)
        Files.createDirectories(basePath)
        return Files.createTempDirectory(basePath, "skill-source-")
    }

    private fun cleanupTmpDir(tmpDir: Path) {
        try {
            tmpDir.toFile().deleteRecursively()
        } catch (e: Exception) {
            log.warn("Failed to cleanup tmp dir: {}", tmpDir, e)
        }
    }

    private companion object {
        /** Keys that never leave the server but must survive a partial configuration update. */
        val INTERNAL_CONFIG_KEYS = listOf("originalFilename")
    }
}
