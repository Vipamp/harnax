package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillSourceService
import com.agnetix.harnax.admin.skill.SkillSourceConfigs
import com.agnetix.harnax.admin.skill.loader.SkillLoaderRegistry
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.CliSkillBindingMapper
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
    private val agentSkillBindingMapper: AgentSkillBindingMapper,
    private val cliSkillBindingMapper: CliSkillBindingMapper,
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
        PageHelper.startPage<SkillRepository>(pageNum, pageSize)
        val entityPage = Page.fromPageInfo(
            skillRepositoryMapper.selectRepositoryList(name, status, currentUsername, tenantId),
        )
        return entityPage.mapRecords { convertToResponse(it) }
    }

    override fun getSkillSource(id: Long): SkillRepository? = skillRepositoryMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createSkillSource(request: SkillSourceCreateRequest): SkillRepository {
        log.info("Creating skill source, name: {}, type: {}", request.name, request.sourceType)

        val tenantId = TenantContext.getTenantId() ?: 1

        val existing = skillRepositoryMapper.selectByName(request.name, tenantId)
        if (existing != null) {
            throw BizException("Source name already exists")
        }

        val config = buildConfigMap(request.sourceType, request.sourceConfig, request.url, request.branch)

        val loader = skillLoaderRegistry.getLoader(request.sourceType)
        loader.validateConfig(config)

        val repository = SkillRepository()
        repository.tenantId = tenantId
        repository.name = request.name
        repository.sourceType = request.sourceType
        repository.sourceConfig = objectMapper.writeValueAsString(config)
        repository.version = request.version ?: ""
        repository.description = request.description
        repository.status = request.status ?: 1
        repository.isPublic = request.isPublic ?: 0
        repository.active = 1
        repository.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""

        if (request.sourceType == "GIT") {
            repository.url = config["url"] as? String ?: ""
            repository.branch = config["branch"] as? String ?: "main"
        }

        skillRepositoryMapper.insert(repository)

        installSkills(repository, config)

        log.info("Skill source created, id: {}", repository.id)
        return repository
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateSkillSource(id: Long, request: SkillSourceUpdateRequest): Boolean {
        log.info("Updating skill source, id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")
        requireWritable(repository)

        if (request.name != null && request.name != repository.name) {
            if (BuiltinRepository.isBuiltin(request.name)) {
                throw BizException("Repository name '${BuiltinRepository.CLI_SKILLS}' is reserved for the platform")
            }
            val existing = skillRepositoryMapper.selectByName(request.name, repository.tenantId)
            if (existing != null) {
                throw BizException("Source name already exists")
            }
            repository.name = request.name
        }

        request.description?.let { repository.description = it }
        request.version?.let { repository.version = it }
        request.url?.let { repository.url = it }
        request.branch?.let { repository.branch = it }
        request.isPublic?.let { repository.isPublic = it }

        // status is not part of the updateById statement; it has a dedicated update
        request.status?.let { newStatus ->
            if (newStatus != repository.status) {
                skillRepositoryMapper.updateStatus(id, newStatus)
            }
        }

        request.sourceConfig?.let { config ->
            // Empty config means the client didn't edit it (e.g. ZIP edit form)
            if (config.isNotEmpty()) {
                // Preserve internal keys that are filtered out of API responses
                val existing = SkillSourceConfigs.parse(repository)
                val merged = config.toMutableMap()
                for (key in listOf("zipPath", "originalFilename")) {
                    if (!merged.containsKey(key)) {
                        existing[key]?.let { merged[key] = it }
                    }
                }
                skillLoaderRegistry.getLoader(repository.sourceType).validateConfig(merged)
                repository.sourceConfig = objectMapper.writeValueAsString(merged)
            }
        }

        return skillRepositoryMapper.updateById(repository) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkillSource(id: Long): Boolean {
        log.info("Deleting skill source, id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")
        requireWritable(repository)

        val skills = skillMapper.selectByRepositoryId(id)
        // Remove agent/cli references first so no dangling bindings survive the delete
        val skillIds = skills.map { it.id }
        if (skillIds.isNotEmpty()) {
            agentSkillBindingMapper.deleteBySkillIds(skillIds)
            cliSkillBindingMapper.deleteBySkillIds(skillIds)
        }
        skills.forEach { skill -> skillMapper.deleteById(skill.id) }

        return skillRepositoryMapper.deleteById(id) > 0
    }

    /**
     * The builtin CLI skill repository is platform-managed and read-only;
     * other repositories may only be written by their own tenant.
     */
    private fun requireWritable(repository: SkillRepository) {
        if (BuiltinRepository.isBuiltin(repository.name)) {
            throw BizException("Builtin repository '${BuiltinRepository.CLI_SKILLS}' is read-only")
        }
        val currentTenantId = TenantContext.getTenantId()
        if (currentTenantId != null && repository.tenantId != currentTenantId) {
            throw BizException("Skill repository belongs to another tenant")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleStatus(id: Long, status: Int): Boolean {
        log.info("Toggling skill source status, id: {}, status: {}", id, status)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")
        requireWritable(repository)

        return skillRepositoryMapper.updateStatus(id, status) > 0
    }

    override fun fetchSkills(id: Long): List<SyncSkillResponse> {
        log.info("Fetching skills from source, id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")

        val config = SkillSourceConfigs.parse(repository)
        val loader = skillLoaderRegistry.getLoader(repository.sourceType)
        val tmpDir = getTmpDir()
        val existingNames = skillMapper.selectByRepositoryId(id).map { it.name }.toSet()

        return try {
            val agentSkills = loader.loadSkills(config, tmpDir)
            agentSkills.map { skill ->
                SyncSkillResponse(
                    name = skill.name,
                    description = skill.description,
                    skillmd = skill.skillContent,
                    resources = skill.resources,
                    exists = existingNames.contains(skill.name),
                )
            }
        } finally {
            cleanupTmpDir(tmpDir)
        }
    }

    override fun uploadAndInstall(zipPath: String, originalFilename: String, name: String): SkillRepository {
        log.info("Installing skill from ZIP upload: {}", originalFilename)

        val tenantId = TenantContext.getTenantId() ?: 1
        val existing = skillRepositoryMapper.selectByName(name, tenantId)
        if (existing != null) {
            throw BizException("Source name already exists")
        }

        // The ZIP is only a transport: its skills are persisted into MySQL below and the
        // uploaded temp file is discarded by the caller, so no path is recorded here
        val config = mapOf<String, Any>(
            "zipPath" to zipPath,
            "originalFilename" to originalFilename,
        )

        val repository = SkillRepository()
        repository.tenantId = tenantId
        repository.name = name
        repository.sourceType = "ZIP"
        repository.sourceConfig = objectMapper.writeValueAsString(config)
        repository.description = "Uploaded ZIP: $originalFilename"
        repository.status = 1
        repository.isPublic = 0
        repository.active = 1
        repository.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""

        skillRepositoryMapper.insert(repository)
        installSkills(repository, config)

        return repository
    }

    override fun convertToResponse(entity: SkillRepository): SkillSourceResponse = SkillSourceResponse.fromEntity(entity)

    private fun installSkills(repository: SkillRepository, config: Map<String, Any>) {
        val loader = skillLoaderRegistry.getLoader(repository.sourceType)
        val tmpDir = getTmpDir()

        try {
            val agentSkills = loader.loadSkills(config, tmpDir)

            for (agentSkill in agentSkills) {
                try {
                    val existingSkill = skillMapper.selectByNameAndRepo(agentSkill.name, repository.id)

                    if (existingSkill != null) {
                        existingSkill.description = agentSkill.description ?: ""
                        existingSkill.skillmd = agentSkill.skillContent ?: ""
                        existingSkill.resources = objectMapper.writeValueAsString(agentSkill.resources ?: emptyMap<String, String>())
                        existingSkill.version = repository.version
                        skillMapper.updateById(existingSkill)
                        log.info("Updated skill: {}", agentSkill.name)
                    } else {
                        val skill = Skill()
                        skill.tenantId = TenantContext.getTenantId() ?: 1
                        skill.name = agentSkill.name
                        skill.repositoryId = repository.id
                        skill.description = agentSkill.description ?: ""
                        skill.skillmd = agentSkill.skillContent ?: ""
                        skill.resources = objectMapper.writeValueAsString(agentSkill.resources ?: emptyMap<String, String>())
                        skill.version = repository.version
                        skill.status = 1
                        skill.active = 1
                        skill.creator = repository.creator
                        skillMapper.insert(skill)
                        log.info("Created skill: {}", agentSkill.name)
                    }
                } catch (e: Exception) {
                    log.error("Failed to install skill {}: {}", agentSkill.name, e.message, e)
                }
            }

            skillRepositoryMapper.updateById(repository)
        } finally {
            cleanupTmpDir(tmpDir)
        }
    }

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
}
