package com.vipamp.vipclaw.admin.service.impl

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.entity.SysUser
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SysUserMapper
import com.vipamp.vipclaw.admin.service.SysUserService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils.hasText

/**
 * 用户服务实现类
 *
 * @author vipamp
 * @since 2026-03-05
 */
@Service
class SysUserServiceImpl : ServiceImpl<SysUserMapper, SysUser>(), SysUserService {

    private val log = LoggerFactory.getLogger(SysUserServiceImpl::class.java)

    override fun getUserPage(
        keyword: String?,
        status: Int?,
        current: Int,
        size: Int
    ): Page<SysUser> {
        log.info("分页查询用户列表，current: {}, size: {}, keyword: {}, status: {}", current, size, keyword, status)

        val page = Page<SysUser>(current.toLong(), size.toLong())
        val wrapper = LambdaQueryWrapper<SysUser>()

        // 模糊查询
        if (hasText(keyword)) {
            wrapper.and { w ->
                w.like(SysUser::username, keyword)
                    .or().like(SysUser::nickname, keyword)
                    .or().like(SysUser::email, keyword)
                    .or().like(SysUser::phone, keyword)
            }
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(SysUser::status, status)
        }

        // 强制校验 active 字段
        wrapper.eq(SysUser::active, 1)
        wrapper.orderByDesc(SysUser::updateTime)
        return this.page(page, wrapper)
    }

    override fun getUserById(id: Long): SysUser {
        log.info("查询用户详情，id: {}", id)

        // 强制校验 active 字段
        val wrapper = LambdaQueryWrapper<SysUser>()
        wrapper.eq(SysUser::id, id)
            .eq(SysUser::active, 1)
        wrapper.last("LIMIT 1")

        val user = this.getOne(wrapper)
            ?: throw BizException("用户不存在")
        return user
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createUser(request: SysUserCreateRequest): Boolean {
        log.info("创建用户，username: {}", request.username)

        // 检查用户名是否存在（需要同时校验 active 字段）
        val existUser = getByUsername(request.username)
        if (existUser != null) {
            throw BizException("用户名已存在")
        }

        val user = SysUser()
        user.username = request.username
        user.password = request.password
        user.nickname = request.nickname
        user.email = request.email
        user.phone = request.phone
        user.gender = request.gender ?: 2
        user.status = request.status ?: 1 // 默认启用
        user.isAdmin = request.isAdmin ?: 0 // 默认非管理员
        user.active = 1  // 默认生效
        user.avatar = request.avatar

        val success = this.save(user)
        log.info("用户创建{}，userId: {}", if (success) "成功" else "失败", user.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateUser(id: Long, request: SysUserUpdateRequest): Boolean {
        log.info("更新用户，id: {}", id)

        // 强制校验 active 字段
        val queryWrapper = LambdaQueryWrapper<SysUser>()
        queryWrapper.eq(SysUser::id, id)
            .eq(SysUser::active, 1)
        queryWrapper.last("LIMIT 1")

        val user = this.getOne(queryWrapper)
            ?: throw BizException("用户不存在")

        // 如果请求中包含用户名且与当前用户名不同，检查新用户名是否已被使用
        if (request.username != null && request.username != user.username) {
            val existUser = getByUsername(request.username)
            if (existUser != null) {
                throw BizException("用户名已存在")
            }
            user.username = request.username
        }

        // 选择性更新字段
        request.nickname?.let { user.nickname = it }
        request.email?.let { user.email = it }
        request.phone?.let { user.phone = it }
        request.gender?.let { user.gender = it }
        request.status?.let { user.status = it }
        request.isAdmin?.let { user.isAdmin = it }
        request.avatar?.let { user.avatar = it }

        val success = this.updateById(user)
        log.info("用户更新{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleUserStatus(id: Long, status: Int): Boolean {
        log.info("切换用户状态，id: {}, status: {}", id, status)

        // 强制校验 active 字段
        val queryWrapper = LambdaQueryWrapper<SysUser>()
        queryWrapper.eq(SysUser::id, id)
            .eq(SysUser::active, 1)
        queryWrapper.last("LIMIT 1")

        val user = this.getOne(queryWrapper)
            ?: throw BizException("用户不存在")

        val wrapper = LambdaUpdateWrapper<SysUser>()
        wrapper.set(SysUser::status, status)
            .eq(SysUser::id, id)
            .eq(SysUser::active, 1)
        return this.update(wrapper)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteUser(id: Long): Boolean {
        log.info("删除用户，id: {}", id)

        // 强制校验 active 字段
        val queryWrapper = LambdaQueryWrapper<SysUser>()
        queryWrapper.eq(SysUser::id, id)
            .eq(SysUser::active, 1)
        queryWrapper.last("LIMIT 1")

        val user = this.getOne(queryWrapper)
            ?: throw BizException("用户不存在")

        val wrapper = LambdaUpdateWrapper<SysUser>()
        wrapper.set(SysUser::active, 0)
            .eq(SysUser::id, id)
            .eq(SysUser::active, 1)
        return this.update(wrapper)
    }

    override fun getByUsername(username: String): SysUser? {
        val wrapper = LambdaQueryWrapper<SysUser>()
        wrapper.eq(SysUser::username, username)
            .eq(SysUser::active, 1)
        wrapper.last("LIMIT 1")
        return getOne(wrapper)
    }
}
