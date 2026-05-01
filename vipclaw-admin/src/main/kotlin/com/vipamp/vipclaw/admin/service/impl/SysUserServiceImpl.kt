package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserResponse
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.entity.Agent
import com.vipamp.vipclaw.admin.entity.SysUser
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.i18n.MessageUtil
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
    private val sysUserMapper: SysUserMapper,
    private val messageUtil: MessageUtil
) : SysUserService {

    private val log = LoggerFactory.getLogger(SysUserServiceImpl::class.java)

    override fun page(
        keyword: String?,
        status: Int?,
        tenantId: Long?,
        pageNum: Int,
        pageSize: Int
    ): Page<SysUser> {
        log.info(
            "分页查询用户列表，pageNum: {}, pageSize: {}, keyword: {}, status: {}, tenantId: {}",
            pageNum,
            pageSize,
            keyword,
            status,
            tenantId
        )
        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(sysUserMapper.selectUserList(keyword, status, tenantId))
    }

    override fun getSysUser(id: Long): SysUser? {
        return sysUserMapper.selectById(id)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createUser(request: SysUserCreateRequest, isPersonal: Boolean): Boolean {
        log.info("创建用户，username: {}, isPersonal: {}", request.username, isPersonal)

        // 企业版和公网版：email 和 phone 必填且进行格式校验
        if (!isPersonal) {
            // 校验 email 必填
            if (request.email.isNullOrBlank()) {
                throw BizException("邮箱不能为空")
            }

            // 校验 phone 必填
            if (request.phone.isNullOrBlank()) {
                throw BizException("手机号不能为空")
            }

            // 校验 email 格式
            val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
            if (!emailRegex.matches(request.email)) {
                throw BizException("邮箱格式不正确")
            }

            // 校验 phone 格式
            val phoneRegex = Regex("^1[3-9]\\d{9}$")
            if (!phoneRegex.matches(request.phone)) {
                throw BizException("手机号格式不正确")
            }
        }

        // 检查用户名是否存在（需要同时校验 active 字段）
        val existUser = getByUsername(request.username)
        if (existUser != null) {
            throw BizException(messageUtil.getMessage("error.user.username_exists"))
        }

        // 检查手机号是否已存在（如果提供了手机号）
        if (!request.phone.isNullOrBlank()) {
            val existPhone = sysUserMapper.selectByPhone(request.phone)
            if (existPhone != null) {
                throw BizException(messageUtil.getMessage("error.user.phone_exists"))
            }
        }

        // 检查邮箱是否已存在（如果提供了邮箱）
        if (!request.email.isNullOrBlank()) {
            val existEmail = sysUserMapper.selectByEmail(request.email)
            if (existEmail != null) {
                throw BizException(messageUtil.getMessage("error.user.email_exists"))
            }
        }

        val user = SysUser()
        user.username = request.username
        // 前端已对密码进行 SHA-256 加密，后端再进行 BCrypt 加密
        // 这样数据库中存储的是 BCrypt(SHA-256(明文密码))
        user.password = BCrypt.hashpw(request.password, BCrypt.gensalt())
        user.nickname = request.nickname
        user.email = request.email ?: ""
        user.phone = request.phone ?: ""
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
    override fun updateUser(id: Long, request: SysUserUpdateRequest, isPersonal: Boolean): Boolean {
        log.info("更新用户，id: {}, isPersonal: {}", id, isPersonal)

        val user = sysUserMapper.selectById(id)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        // 企业版和公开版：email 和 phone 必填且进行格式校验
        if (!isPersonal) {
            // 校验 email 必填
            if (request.email.isNullOrBlank()) {
                throw BizException(messageUtil.getMessage("error.validation.required", "邮箱"))
            }

            // 校验 phone 必填
            if (request.phone.isNullOrBlank()) {
                throw BizException(messageUtil.getMessage("error.validation.required", "手机号"))
            }

            // 校验 email 格式
            val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
            if (!emailRegex.matches(request.email)) {
                throw BizException(messageUtil.getMessage("error.validation.email_invalid"))
            }

            // 校验 phone 格式
            val phoneRegex = Regex("^1[3-9]\\d{9}$")
            if (!phoneRegex.matches(request.phone)) {
                throw BizException(messageUtil.getMessage("error.validation.phone_invalid"))
            }

            // 检查 email 是否已被其他用户使用
            if (request.email != user.email) {
                val existEmail = sysUserMapper.selectByEmail(request.email)
                if (existEmail != null) {
                    throw BizException(messageUtil.getMessage("error.user.email_exists"))
                }
            }

            // 检查 phone 是否已被其他用户使用
            if (request.phone != user.phone) {
                val existPhone = sysUserMapper.selectByPhone(request.phone)
                if (existPhone != null) {
                    throw BizException(messageUtil.getMessage("error.user.phone_exists"))
                }
            }
        } else {
            // 个人版：如果修改了 email 或 phone，才进行格式校验和唯一性校验
            if (!request.email.isNullOrBlank() && request.email != user.email) {
                val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
                if (!emailRegex.matches(request.email)) {
                    throw BizException(messageUtil.getMessage("error.validation.email_invalid"))
                }
                val existEmail = sysUserMapper.selectByEmail(request.email)
                if (existEmail != null) {
                    throw BizException(messageUtil.getMessage("error.user.email_exists"))
                }
            }

            if (!request.phone.isNullOrBlank() && request.phone != user.phone) {
                val phoneRegex = Regex("^1[3-9]\\d{9}$")
                if (!phoneRegex.matches(request.phone)) {
                    throw BizException("手机号格式不正确")
                }
                val existPhone = sysUserMapper.selectByPhone(request.phone)
                if (existPhone != null) {
                    throw BizException("手机号已存在")
                }
            }
        }

        // 选择性更新字段（username 不允许修改）
        request.nickname?.let { user.nickname = it }
        request.email?.let { user.email = it }
        request.phone?.let { user.phone = it }
        request.gender?.let { user.gender = it }
        request.isAdmin?.let { user.isAdmin = it }
        request.avatar?.let { user.avatar = it }

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

        // 不允许删除管理员用户
        if (user.isAdmin == 1) {
            throw BizException("不允许删除管理员用户")
        }

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
