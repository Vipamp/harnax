package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.SkillRepositoryCreateRequest
import com.vipamp.vipclaw.admin.dto.SkillRepositoryResponse
import com.vipamp.vipclaw.admin.dto.SkillRepositoryUpdateRequest
import com.vipamp.vipclaw.admin.dto.SyncSkillResponse
import com.vipamp.vipclaw.admin.entity.SkillRepository
import com.vipamp.vipclaw.admin.entity.SysJob
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SkillRepositoryMapper
import com.vipamp.vipclaw.admin.service.SkillRepositoryService
import com.vipamp.vipclaw.admin.util.GitSkillLoader.loadSkillsFromGit
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.util.stream.Collectors

/**
 * 技能仓库服务实现类
 *
 * @author vipamp
 * @since 2026-03-16
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
            "分页查询技能仓库列表，pageNum: {}, pageSize: {}, name: {}, status: {}",
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
        log.info("创建技能仓库，name: {}", request.name)

        // 检查仓库名称是否存在
        val existRepository = skillRepositoryMapper.selectByName(request.name!!)
        if (existRepository != null) {
            throw BizException("仓库名称已存在")
        }

        val repository = SkillRepository()
        repository.name = request.name
        repository.url = request.url
        repository.branch = request.branch
        repository.description = request.description
        repository.status = request.status ?: 1 // 默认启用
        repository.active = 1 // 默认生效

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        repository.creator = currentUsername

        val success = this.skillRepositoryMapper.insert(repository) > 0
        log.info("技能仓库创建{}，repositoryId: {}", if (success) "成功" else "失败", repository.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateSkillRepository(id: Long, request: SkillRepositoryUpdateRequest): Boolean {
        log.info("更新技能仓库，id: {}", id)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("技能仓库不存在")

        // 如果请求中包含仓库名称且与当前仓库名称不同，检查新仓库名称是否已被使用
        if (request.name != null && request.name != repository.name) {
            val existRepository = skillRepositoryMapper.selectByName(request.name!!)
            if (existRepository != null) {
                throw BizException("仓库名称已存在")
            }
            repository.name = request.name
        }

        // 选择性更新字段
        request.url?.let { repository.url = it }
        request.branch?.let { repository.branch = it }
        request.description?.let { repository.description = it }

        val success = this.skillRepositoryMapper.updateById(repository) > 0
        log.info("技能仓库更新{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleSkillRepository(id: Long, status: Int): Boolean {
        log.info("切换技能仓库状态，id: {}, status: {}", id, status)

        val repository = skillRepositoryMapper.selectById(id)
            ?: throw BizException("技能仓库不存在")

        return skillRepositoryMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkillRepository(id: Long): Boolean {
        log.info("删除技能仓库，id: {}", id)
        return skillRepositoryMapper.deleteById(id) > 0
    }

    override fun getByName(name: String): SkillRepository? = skillRepositoryMapper.selectByName(name)

    override fun fetchRemoteSkills(repositoryId: Long): List<SyncSkillResponse> {
        log.info("获取远程技能列表，repositoryId: {}", repositoryId)
        val repository = skillRepositoryMapper.selectById(repositoryId)
            ?: throw BizException("技能仓库不存在")
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
