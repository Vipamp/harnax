package com.vipamp.vipclaw.admin.service.impl

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import com.vipamp.vipclaw.admin.dto.SkillRepositoryCreateRequest
import com.vipamp.vipclaw.admin.dto.SkillRepositoryUpdateRequest
import com.vipamp.vipclaw.admin.dto.SyncSkillResponse
import com.vipamp.vipclaw.admin.entity.SkillRepository
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SkillRepositoryMapper
import com.vipamp.vipclaw.admin.service.SkillRepositoryService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import com.vipamp.vipclaw.common.entity.SkillRepository
import com.vipamp.vipclaw.common.mapper.SkillRepositoryMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils.hasText

/**
 * 技能仓库服务实现类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Service
class SkillRepositoryServiceImpl(
    private val jwtUtil: JwtUtil
) : ServiceImpl<SkillRepositoryMapper, SkillRepository>(), SkillRepositoryService {

    private val log = LoggerFactory.getLogger(SkillRepositoryServiceImpl::class.java)

    override fun getRepositoryPage(
        name: String?,
        status: Int?,
        current: Int,
        size: Int
    ): Page<SkillRepository> {
        log.info("分页查询技能仓库列表，current: {}, size: {}, name: {}, status: {}", current, size, name, status)

        val page = Page<SkillRepository>(current.toLong(), size.toLong())
        val wrapper = LambdaQueryWrapper<SkillRepository>()

        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 权限过滤：只查询公开的或自己创建的
        wrapper.and { w ->
            w.eq(SkillRepository::isPublic, 1)
                .or()
                .eq(SkillRepository::creator, currentUsername)
        }

        // 仓库名称模糊查询
        if (hasText(name)) {
            wrapper.like(SkillRepository::name, name)
        }

        // 状态筛选
        status?.let { wrapper.eq(SkillRepository::status, it) }

        // 强制校验 active 字段
        wrapper.eq(SkillRepository::active, 1)
        wrapper.orderByDesc(SkillRepository::status)
            .orderByDesc(SkillRepository::updateTime)
        return this.page(page, wrapper)
    }

    override fun getActiveRepositories(): List<SkillRepository> {
        val wrapper = LambdaQueryWrapper<SkillRepository>()
        wrapper.eq(SkillRepository::active, 1)
            .eq(SkillRepository::status, 1)
            .orderByDesc(SkillRepository::updateTime)
        return this.list(wrapper)
    }

    override fun getRepositoryById(id: Long): SkillRepository {
        log.info("查询技能仓库详情，id: {}", id)

        // 强制校验 active 字段
        val wrapper = LambdaQueryWrapper<SkillRepository>()
        wrapper.eq(SkillRepository::id, id)
            .eq(SkillRepository::active, 1)
        wrapper.last("LIMIT 1")

        val repository = this.getOne(wrapper)
            ?: throw BizException("技能仓库不存在")
        return repository
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createRepository(request: SkillRepositoryCreateRequest): Boolean {
        log.info("创建技能仓库，name: {}", request.name)

        // 检查仓库名称是否存在（需要同时校验 active 字段）
        val existRepository = getByName(request.name)
        if (existRepository != null) {
            throw BizException("仓库名称已存在")
        }

        val repository = SkillRepository()
        repository.name = request.name
        repository.url = request.url
        repository.branch = request.branch
        repository.description = request.description
        repository.status = request.status ?: 1 // 默认启用
        repository.active = 1  // 默认生效

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        repository.creator = currentUsername

        // 默认不公开
        if (repository.isPublic == null) {
            repository.isPublic = 0
        }

        val success = this.save(repository)
        log.info("技能仓库创建{}，repositoryId: {}", if (success) "成功" else "失败", repository.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateRepository(id: Long, request: SkillRepositoryUpdateRequest): Boolean {
        log.info("更新技能仓库，id: {}", id)

        // 强制校验 active 字段
        val queryWrapper = LambdaQueryWrapper<SkillRepository>()
        queryWrapper.eq(SkillRepository::id, id)
            .eq(SkillRepository::active, 1)
        queryWrapper.last("LIMIT 1")

        val repository = this.getOne(queryWrapper)
            ?: throw BizException("技能仓库不存在")

        // 如果请求中包含仓库名称且与当前仓库名称不同，检查新仓库名称是否已被使用
        if (request.name != null && request.name != repository.name) {
            val existRepository = getByName(request.name)
            if (existRepository != null) {
                throw BizException("仓库名称已存在")
            }
            repository.name = request.name
        }

        // 选择性更新字段
        request.url?.let { repository.url = it }
        request.branch?.let { repository.branch = it }
        request.description?.let { repository.description = it }
        request.status?.let { repository.status = it }

        val success = this.updateById(repository)
        log.info("技能仓库更新{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleRepositoryStatus(id: Long, status: Int): Boolean {
        log.info("切换技能仓库状态，id: {}, status: {}", id, status)

        // 强制校验 active 字段
        val queryWrapper = LambdaQueryWrapper<SkillRepository>()
        queryWrapper.eq(SkillRepository::id, id)
            .eq(SkillRepository::active, 1)
        queryWrapper.last("LIMIT 1")

        val repository = this.getOne(queryWrapper)
            ?: throw BizException("技能仓库不存在")

        val wrapper = LambdaUpdateWrapper<SkillRepository>()
        wrapper.set(SkillRepository::status, status)
            .eq(SkillRepository::id, id)
            .eq(SkillRepository::active, 1)
        return this.update(wrapper)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteRepository(id: Long): Boolean {
        log.info("删除技能仓库，id: {}", id)

        // 强制校验 active 字段
        val queryWrapper = LambdaQueryWrapper<SkillRepository>()
        queryWrapper.eq(SkillRepository::id, id)
            .eq(SkillRepository::active, 1)
        queryWrapper.last("LIMIT 1")

        val repository = this.getOne(queryWrapper)
            ?: throw BizException("技能仓库不存在")

        val wrapper = LambdaUpdateWrapper<SkillRepository>()
        wrapper.set(SkillRepository::active, 0)
            .eq(SkillRepository::id, id)
            .eq(SkillRepository::active, 1)
        return this.update(wrapper)
    }

    override fun getByName(name: String): SkillRepository? {
        val wrapper = LambdaQueryWrapper<SkillRepository>()
        wrapper.eq(SkillRepository::name, name)
            .eq(SkillRepository::active, 1)
        wrapper.last("LIMIT 1")
        return getOne(wrapper)
    }

    override fun fetchRemoteSkills(repositoryId: Long): List<SyncSkillResponse> {
        log.info("获取远程技能列表，repositoryId: {}", repositoryId)

        // TODO: 实现真实的 Git 仓库拉取和 skill.md 解析逻辑
        // 目前返回 Mock 测试数据

        val mockData = mutableListOf<SyncSkillResponse>()

        val skill1 = SyncSkillResponse()
        skill1.name = "Java 编程助手"
        skill1.description = "提供 Java 编程相关的技能帮助，包括代码编写、调试、优化等"
        skill1.skillmd = "# Java 编程助手\n\n我可以帮助你：\n- Java 基础语法\n- Spring 框架\n- 多线程编程\n- JVM 调优"
        skill1.resources = "[]"
        skill1.exists = false
        mockData.add(skill1)

        val skill2 = SyncSkillResponse()
        skill2.name = "Python 脚本专家"
        skill2.description = "Python 脚本编写和问题解答，涵盖数据分析、自动化等领域"
        skill2.skillmd = "# Python 脚本专家\n\n擅长领域：\n- Python 基础\n- Django/Flask\n- 数据处理\n- 自动化脚本"
        skill2.resources = "[]"
        skill2.exists = false
        mockData.add(skill2)

        val skill3 = SyncSkillResponse()
        skill3.name = "前端 UI 设计师"
        skill3.description = "前端界面设计和样式咨询，精通 React、Vue 等主流框架"
        skill3.skillmd = "# 前端 UI 设计师\n\n专业技能：\n- React/Vue\n- CSS/Tailwind\n- 响应式设计\n- 用户体验优化"
        skill3.resources = "{\"ref/doc1.md\":\"## 1. 简介\",\"ref/doc2.md\":\"## 2. 正文\"}"
        skill3.exists = true // 模拟已存在的技能
        mockData.add(skill3)

        log.info("Mock 数据返回，共 {} 个技能", mockData.size)
        return mockData
    }
}
