package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserResponse
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.entity.Agent
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

    override fun page(
        keyword: String?,
        status: Int?,
        pageNum: Int,
        pageSize: Int
    ): Page<SysUser> {
        log.info(
            "分页查询用户列表，pageNum: {}, pageSize: {}, keyword: {}, status: {}",
            pageNum,
            pageSize,
            keyword,
            status
        )
        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(sysUserMapper.selectUserList(keyword, status))
    }

    override fun getSysUser(id: Long): SysUser? {
        return sysUserMapper.selectById(id)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createUser(request: SysUserCreateRequest): Boolean {
        log.info("创建用户，username: {}", request.username)

        // 检查用户名是否存在（需要同时校验 active 字段）
        val existUser = getByUsername(request.username)
        if (existUser != null) {
            throw BizException("用户名已存在")
        }

        // 检查手机号是否已存在
        if (request.phone.isNotBlank()) {
            val existPhone = sysUserMapper.selectByPhone(request.phone)
            if (existPhone != null) {
                throw BizException("手机号已存在")
            }
        }

        // 检查邮箱是否已存在
        if (request.email.isNotBlank()) {
            val existEmail = sysUserMapper.selectByEmail(request.email)
            if (existEmail != null) {
                throw BizException("邮箱已存在")
            }
        }

        val user = SysUser()
        user.username = request.username
        // 前端已对密码进行 SHA-256 加密，后端再进行 BCrypt 加密
        // 这样数据库中存储的是 BCrypt(SHA-256(明文密码))
        user.password = BCrypt.hashpw(request.password, BCrypt.gensalt())
        user.nickname = request.nickname
        user.email = request.email
        user.phone = request.phone
        user.gender = request.gender ?: 2
        user.status = 1 // 默认启用
        user.isAdmin = 0 // 默认非管理员
        user.active = 1  // 默认生效
        user.avatar = request.avatar

        val success = this.sysUserMapper.insert(user) > 0
        log.info("用户创建{}，userId: {}", if (success) "成功" else "失败", user.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateUser(id: Long, request: SysUserUpdateRequest): Boolean {
        log.info("更新用户，id: {}", id)

        val user = sysUserMapper.selectById(id)
            ?: throw BizException("用户不存在")

        // 如果修改了手机号，检查是否已被其他用户使用
        if (!request.phone.isNullOrBlank() && request.phone != user.phone) {
            val existPhone = sysUserMapper.selectByPhone(request.phone)
            if (existPhone != null) {
                throw BizException("手机号已存在")
            }
        }

        // 如果修改了邮箱，检查是否已被其他用户使用
        if (!request.email.isNullOrBlank() && request.email != user.email) {
            val existEmail = sysUserMapper.selectByEmail(request.email)
            if (existEmail != null) {
                throw BizException("邮箱已存在")
            }
        }

        // 选择性更新字段（username 不允许修改）
        request.nickname?.let { user.nickname = it }
        request.email?.let { user.email = it }
        request.phone?.let { user.phone = it }
        request.gender?.let { user.gender = it }
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
        return sysUserMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteUser(id: Long): Boolean {
        log.info("删除用户，id: {}", id)
        val user = sysUserMapper.selectById(id)
            ?: throw BizException("用户不存在")
        return sysUserMapper.deleteById(id) > 0
    }

    override fun convertToResponse(sysUser: SysUser): SysUserResponse {
        return SysUserResponse.fromEntity(sysUser)
    }

    override fun getByUsername(username: String): SysUser? {
        return sysUserMapper.selectByUsername(username)
    }

    override fun existsByUsername(username: String): Boolean {
        return sysUserMapper.selectByUsername(username) != null
    }

    override fun existsByPhone(phone: String): Boolean {
        return sysUserMapper.selectByPhone(phone) != null
    }

    override fun existsByEmail(email: String): Boolean {
        return sysUserMapper.selectByEmail(email) != null
    }
}
