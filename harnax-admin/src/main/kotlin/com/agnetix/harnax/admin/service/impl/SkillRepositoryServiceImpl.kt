package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillRepositoryCreateRequest
import com.agnetix.harnax.admin.dto.SkillRepositoryResponse
import com.agnetix.harnax.admin.dto.SkillRepositoryUpdateRequest
import com.agnetix.harnax.admin.dto.SyncSkillResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.util.GitSkillLoader.loadSkillsFromGit
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.entity.SysJob
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.util.stream.Collectors

/**
 * Skill repository service implementation
 */
@Service
class SkillRepositoryServiceImpl(
    private val jwtUtil: JwtUtil,
    private val skillRepositoryMapper: SkillRepositoryMapper,
    @Value($$"${local.tmp-dir}") private val localTmpDir: String?,
) : SkillRepositoryService {

    private val log = LoggerFactory.getLogger(SkillRepositoryServiceImpl::class.java)

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
        PageHelper.startPage<SysJob>(pageNum, pageSize)
        return Page.fromPageInfo(skillRepositoryMapper.selectRepositoryList(name, status, currentUsername))
    }

    override fun getActiveRepositories(): List<SkillRepository> = skillRepositoryMapper.selectActiveRepositories()

    override fun getSkillRepository(id: Long): SkillRepository? = skillRepositoryMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createSkillRepository(request: SkillRepositoryCreateRequest): Boolean {
        log.info("Creating skill repository, name: {}", request.name)

        // Check if repository name already exists
        val existRepository = skillRepositoryMapper.selectByName(request.name!!)
        if (existRepository != null) {
            throw BizException("Repository name already exists")
        }

        val repository = SkillRepository()
        repository.name = request.name
        repository.url = request.url
        repository.branch = request.branch
        repository.description = request.description
        repository.status = request.status ?: 1 // Default enabled
        repository.active = 1 // Default active

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

        // If request contains repository name and it's different from current name, check if new name is already in use
        if (request.name != null && request.name != repository.name) {
            val existRepository = skillRepositoryMapper.selectByName(request.name!!)
            if (existRepository != null) {
                throw BizException("Repository name already exists")
            }
            repository.name = request.name
        }

        // Selectively update fields
        request.url?.let { repository.url = it }
        request.branch?.let { repository.branch = it }
        request.description?.let { repository.description = it }

        val success = this.skillRepositoryMapper.updateById(repository) > 0
        log.info("Skill repository update {}, id: {}", if (success) "successful" else "failed", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleSkillRepository(id: Long, status: Int): Boolean {
        log.info("Toggling skill repository status, id: {}, status: {}", id, status)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("Skill repository not found")

        return skillRepositoryMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkillRepository(id: Long): Boolean {
        log.info("Deleting skill repository, id: {}", id)
        return skillRepositoryMapper.deleteById(id) > 0
    }

    override fun getByName(name: String): SkillRepository? = skillRepositoryMapper.selectByName(name)

    override fun fetchRemoteSkills(repositoryId: Long): List<SyncSkillResponse> {
        log.info("Fetching remote skill list, repositoryId: {}", repositoryId)
        val repository = skillRepositoryMapper.selectById(repositoryId)
            ?: throw BizException("Skill repository not found")
        val tmpDir = localTmpDir ?: Files.createTempDirectory("git-repo-").toFile().absolutePath
        val allSkills = loadSkillsFromGit(
            repository.url,
            repository.branch,
            tmpDir,
            repository.name,
        ).stream().map {
            SyncSkillResponse(
                name = it.name,
                description = it.description,
                skillmd = it.skillContent,
                resources = it.resources,
            )
        }.collect(Collectors.toList())
        return allSkills
    }

    override fun convertToResponse(skillRepository: SkillRepository): SkillRepositoryResponse = SkillRepositoryResponse.fromEntity(skillRepository)
}
