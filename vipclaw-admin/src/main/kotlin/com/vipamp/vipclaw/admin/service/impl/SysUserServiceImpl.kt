package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.entity.SysUser
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SysUserMapper
import com.vipamp.vipclaw.admin.service.SysUserService
import com.vipamp.vipclaw.common.page.Page
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 用户服务实现类
 *
 * @author vipamp
 * @since 2026-03-05
 */
@Service
class SysUserServiceImpl(
    private val sysUserMapper: SysUserMapper
) : SysUserService {

    private val log = LoggerFactory.getLogger(SysUserServiceImpl::class.java)

    override fun getUserPage(
        keyword: String?,
        status: Int?,
        current: Int,
        size: Int
    ): Page<SysUser> {
        log.info("分页查询用户列表，current: {}, size: {}, keyword: {}, status: {}", current, size, keyword, status)

        // 使用 MyBatis 注解查询
        val allUsers = sysUserMapper.selectUserList(keyword, status)

        // 手动分页
        val page = Page<SysUser>(current.toLong(), size.toLong())
        val total = allUsers.size.toLong()
        page.total = total

        val fromIndex = (current - 1) * size
        val toIndex = minOf(fromIndex + size, allUsers.size)

        if (fromIndex < allUsers.size) {
            page.records = allUsers.subList(fromIndex, toIndex)
        } else {
            page.records = emptyList()
        }

        return page
    }

    override fun getUserById(id: Long): SysUser {
        log.info("查询用户详情，id: {}", id)

        val user = sysUserMapper.selectActiveById(id)
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
        // BCrypt 加密密码
        user.password = BCrypt.hashpw(request.password, BCrypt.gensalt())
        user.nickname = request.nickname
        user.email = request.email
        user.phone = request.phone
        user.gender = request.gender ?: 2
        user.status = 1 // 默认启用
        user.isAdmin = 0 // 默认非管理员
        user.active = 1  // 默认生效
        user.avatar = request.avatar ?: ""

        val success = this.sysUserMapper.insert(user) > 0
        log.info("用户创建{}，userId: {}", if (success) "成功" else "失败", user.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateUser(id: Long, request: SysUserUpdateRequest): Boolean {
        log.info("更新用户，id: {}", id)

        val user = sysUserMapper.selectActiveById(id)
            ?: throw BizException("用户不存在")

        // 选择性更新字段（username 不允许修改）
        request.nickname?.let { user.nickname = it }
        request.email?.let { user.email = it }
        request.phone?.let { user.phone = it }
        request.gender?.let { user.gender = it }
        request.status?.let { user.status = it }
        request.isAdmin?.let { user.isAdmin = it }
        request.avatar?.let { user.avatar = it }
        // 如果提供了密码，进行加密
        request.password?.let { 
            if (it.isNotEmpty()) {
                user.password = BCrypt.hashpw(it, BCrypt.gensalt())
            }
        }

        val success = this.sysUserMapper.updateById(user) > 0
        log.info("用户更新{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleUserStatus(id: Long, status: Int): Boolean {
        log.info("切换用户状态，id: {}, status: {}", id, status)

        val user = sysUserMapper.selectActiveById(id)
            ?: throw BizException("用户不存在")

        val result = sysUserMapper.updateStatus(id, status)
        return result > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteUser(id: Long): Boolean {
        log.info("删除用户，id: {}", id)

        val user = sysUserMapper.selectActiveById(id)
            ?: throw BizException("用户不存在")

        val result = sysUserMapper.logicalDelete(id)
        return result > 0
    }

    override fun getByUsername(username: String): SysUser? {
        return sysUserMapper.selectByUsername(username)
    }
}
