package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillSourceService
import com.agnetix.harnax.admin.skill.loader.SkillLoaderRegistry
import com.agnetix.harnax.admin.skill.store.SkillContent
import com.agnetix.harnax.admin.skill.store.SkillContentStore
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Skill
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
    private val skillContentStore: SkillContentStore,
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
    ): Page<SkillRepository> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        PageHelper.startPage<SkillRepository>(pageNum, pageSize)
        return Page.fromPageInfo(
            skillRepositoryMapper.selectRepositoryList(name, status, currentUsername),
        )
    }

    override fun getSkillSource(id: Long): SkillRepository? = skillRepositoryMapper.selectById(id)

    override fun createSkillSource(request: SkillSourceCreateRequest): SkillRepository {
        log.info("Creating skill source, name: {}, type: {}", request.name, request.sourceType)

        val existing = skillRepositoryMapper.selectByName(request.name)
        if (existing != null) {
            throw BizException("Source name already exists")
        }

        val loader = skillLoaderRegistry.getLoader(request.sourceType)

        val config = buildConfigMap(request.sourceType, request.sourceConfig, request.url, request.branch)
        loader.validateConfig(config)

        val repository = SkillRepository()
        repository.tenantId = TenantContext.getTenantId() ?: 1
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

        if (request.name != null && request.name != repository.name) {
            val existing = skillRepositoryMapper.selectByName(request.name)
            if (existing != null) {
                throw BizException("Source name already exists")
            }
            repository.name = request.name
        }

        request.description?.let { repository.description = it }
        request.version?.let { repository.version = it }
        request.url?.let { repository.url = it }
        request.branch?.let { repository.branch = it }

        request.sourceConfig?.let { config ->
            repository.sourceConfig = objectMapper.writeValueAsString(config)
        }

        return skillRepositoryMapper.updateById(repository) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkillSource(id: Long): Boolean {
        log.info("Deleting skill source, id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")

        val skills = skillMapper.selectByRepositoryId(id)
        skills.forEach { skill ->
            if (skill.storagePath.isNotBlank()) {
                try {
                    skillContentStore.delete(skill.storagePath)
                } catch (e: Exception) {
                    log.warn("Failed to delete skill content for {}: {}", skill.name, e.message)
                }
            }
            skillMapper.deleteById(skill.id)
        }

        return skillRepositoryMapper.deleteById(id) > 0
    }

    override fun fetchSkills(id: Long): List<SyncSkillResponse> {
        log.info("Fetching skills from source, id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill source not found")

        val config = parseConfig(repository)
        val loader = skillLoaderRegistry.getLoader(repository.sourceType)
        val tmpDir = getTmpDir()

        return try {
            val agentSkills = loader.loadSkills(config, tmpDir)
            agentSkills.map { skill ->
                SyncSkillResponse(
                    name = skill.name,
                    description = skill.description,
                    skillmd = skill.skillContent,
                    resources = skill.resources,
                )
            }
        } finally {
            cleanupTmpDir(tmpDir)
        }
    }

    override fun uploadAndInstall(zipPath: String, originalFilename: String, name: String): SkillRepository {
        log.info("Installing skill from ZIP upload: {}", originalFilename)

        val existing = skillRepositoryMapper.selectByName(name)
        if (existing != null) {
            throw BizException("Source name already exists")
        }

        val config = mapOf<String, Any>(
            "zipPath" to zipPath,
            "originalFilename" to originalFilename,
        )

        val repository = SkillRepository()
        repository.tenantId = TenantContext.getTenantId() ?: 1
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

                    val content = SkillContent(
                        skillmd = agentSkill.skillContent ?: "",
                        resources = agentSkill.resources?.mapValues { it.value.toByteArray() } ?: emptyMap(),
                    )
                    val storagePath = skillContentStore.save(repository.id, agentSkill.name, content)

                    if (existingSkill != null) {
                        existingSkill.description = agentSkill.description ?: ""
                        existingSkill.skillmd = agentSkill.skillContent ?: ""
                        existingSkill.resources = objectMapper.writeValueAsString(agentSkill.resources ?: emptyMap<String, String>())
                        existingSkill.storagePath = storagePath
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
                        skill.storagePath = storagePath
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

            repository.storagePath = "${repository.id}"
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

    private fun parseConfig(repository: SkillRepository): Map<String, Any> {
        if (repository.sourceConfig.isNotBlank()) {
            return try {
                objectMapper.readValue(
                    repository.sourceConfig,
                    object : tools.jackson.core.type.TypeReference<Map<String, Any>>() {},
                )
            } catch (e: Exception) {
                log.warn("Failed to parse sourceConfig, falling back to url/branch", e)
                mapOf("url" to repository.url, "branch" to repository.branch)
            }
        }
        return mapOf("url" to repository.url, "branch" to repository.branch)
    }

    private fun getTmpDir(): Path {
        val base = localTmpDir ?: System.getProperty("java.io.tmpdir")
        return Files.createTempDirectory(Path.of(base), "skill-source-")
    }

    private fun cleanupTmpDir(tmpDir: Path) {
        try {
            tmpDir.toFile().deleteRecursively()
        } catch (e: Exception) {
            log.warn("Failed to cleanup tmp dir: {}", tmpDir, e)
        }
    }
}
