package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SysUserCreateRequest
import com.agnetix.harnax.admin.dto.SysUserResponse
import com.agnetix.harnax.admin.dto.SysUserUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.admin.service.SysUserService
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.SysUserMapper
import com.agnetix.harnax.mapper.TenantMapper
import com.agnetix.harnax.mapper.UserTenantMapper
import com.github.pagehelper.PageHelper
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * User service implementation
 */
@Service
class SysUserServiceImpl(
    private val sysUserMapper: SysUserMapper,
    private val userTenantMapper: UserTenantMapper,
    private val tenantMapper: TenantMapper,
    private val messageUtil: MessageUtil,
    private val apiKeyService: ApiKeyService,
) : SysUserService {

    private val log = LoggerFactory.getLogger(SysUserServiceImpl::class.java)

    override fun page(
        keyword: String?,
        status: Int?,
        tenantId: Long?,
        pageNum: Int,
        pageSize: Int,
    ): Page<SysUser> {
        log.info(
            "Paginated query for user list, pageNum: {}, pageSize: {}, keyword: {}, status: {}, tenantId: {}",
            pageNum,
            pageSize,
            keyword,
            status,
            tenantId,
        )
        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(sysUserMapper.selectUserList(keyword, status, tenantId))
    }

    override fun getSysUser(id: Long): SysUser? = sysUserMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createUser(request: SysUserCreateRequest): Boolean {
        log.info("Creating user, username: {}", request.username)

        // Email and phone are required and validated
        // Validate email is required
        if (request.email.isNullOrBlank()) {
            throw BizException(messageUtil.getMessage("error.validation.required", "Email"))
        }

        // Validate phone is required
        if (request.phone.isNullOrBlank()) {
            throw BizException(messageUtil.getMessage("error.validation.required", "Phone"))
        }

        // Validate email format
        val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        if (!emailRegex.matches(request.email)) {
            throw BizException(messageUtil.getMessage("error.validation.email_invalid"))
        }

        // Validate phone format
        val phoneRegex = Regex("^1[3-9]\\d{9}$")
        if (!phoneRegex.matches(request.phone)) {
            throw BizException(messageUtil.getMessage("error.validation.phone_invalid"))
        }

        // Check if username exists (need to validate active field)
        val existUser = getByUsername(request.username)
        if (existUser != null) {
            throw BizException(messageUtil.getMessage("error.user.username_exists"))
        }

        // Check if phone already exists (if phone is provided)
        if (!request.phone.isNullOrBlank()) {
            val existPhone = sysUserMapper.selectByPhone(request.phone)
            if (existPhone != null) {
                throw BizException(messageUtil.getMessage("error.user.phone_exists"))
            }
        }

        // Check if email already exists (if email is provided)
        if (!request.email.isNullOrBlank()) {
            val existEmail = sysUserMapper.selectByEmail(request.email)
            if (existEmail != null) {
                throw BizException(messageUtil.getMessage("error.user.email_exists"))
            }
        }

        val user = SysUser()
        user.username = request.username
        // Frontend has encrypted password with SHA-256, backend then encrypts with BCrypt
        // This way database stores BCrypt(SHA-256(plain password))
        user.password = BCrypt.hashpw(request.password, BCrypt.gensalt())
        user.nickname = request.nickname
        user.email = request.email ?: ""
        user.phone = request.phone ?: ""
        user.gender = request.gender ?: 2
        user.status = 1 // Default enabled
        user.isAdmin = 0 // Default non-admin
        user.active = 1 // Default active
        user.avatar = request.avatar

        val success = this.sysUserMapper.insert(user) > 0
        log.info("User creation {}, userId: {}", if (success) "successful" else "failed", user.id)

        if (success) {
            // Auto-generate permanent API key for the new user
            try {
                apiKeyService.createPermanentKeyForUser(user.id, user.username, user.tenantId)
            } catch (e: Exception) {
                log.error("Failed to create permanent API key for user: {}", user.username, e)
            }
        }

        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateUser(id: Long, request: SysUserUpdateRequest): Boolean {
        log.info("Updating user, id: {}", id)

        val user = sysUserMapper.selectById(id)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        // Email and phone are required and validated
        // Validate email is required
        if (request.email.isNullOrBlank()) {
            throw BizException(messageUtil.getMessage("error.validation.required", "Email"))
        }

        // Validate phone is required
        if (request.phone.isNullOrBlank()) {
            throw BizException(messageUtil.getMessage("error.validation.required", "Phone"))
        }

        // Validate email format
        val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        if (!emailRegex.matches(request.email)) {
            throw BizException(messageUtil.getMessage("error.validation.email_invalid"))
        }

        // Validate phone format
        val phoneRegex = Regex("^1[3-9]\\d{9}$")
        if (!phoneRegex.matches(request.phone)) {
            throw BizException(messageUtil.getMessage("error.validation.phone_invalid"))
        }

        // Check if email is already used by other users
        if (request.email != user.email) {
            val existEmail = sysUserMapper.selectByEmail(request.email)
            if (existEmail != null) {
                throw BizException(messageUtil.getMessage("error.user.email_exists"))
            }
        }

        // Check if phone is already used by other users
        if (request.phone != user.phone) {
            val existPhone = sysUserMapper.selectByPhone(request.phone)
            if (existPhone != null) {
                throw BizException(messageUtil.getMessage("error.user.phone_exists"))
            }
        }

        // Selectively update fields (username is not allowed to be modified)
        request.nickname?.let { user.nickname = it }
        request.email?.let { user.email = it }
        request.phone?.let { user.phone = it }
        request.gender?.let { user.gender = it }
        request.isAdmin?.let { user.isAdmin = it }
        request.avatar?.let { user.avatar = it }

        val success = this.sysUserMapper.updateById(user) > 0
        log.info("User update {}, id: {}", if (success) "successful" else "failed", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleUserStatus(id: Long, status: Int): Boolean {
        log.info("Toggling user status, id: {}, status: {}", id, status)

        // If disabling (status=0), need to perform checks
        if (status == 0) {
            val user = sysUserMapper.selectById(id)
                ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

            // Do not allow disabling system admin users (isAdmin = 1)
            if (user.isAdmin == 1) {
                throw BizException(messageUtil.getMessage("error.user.cannot_disable_admin"))
            }

            // Check if this user is an admin of any tenant
            val userTenants = userTenantMapper.selectByUserId(id)
            val tenantAdminRoles = userTenants.filter { it.role == "admin" }

            if (tenantAdminRoles.isNotEmpty()) {
                // User is an admin of some tenant, not allowed to disable
                // Query all tenant names
                val tenantNames = tenantAdminRoles.mapNotNull { userTenant ->
                    val tenant = tenantMapper.selectById(userTenant.tenantId)
                    tenant?.name
                }

                val tenantNamesStr = tenantNames.joinToString(", ")
                log.warn("User is tenant admin, not allowed to disable, userId: {}, tenantNames: {}", id, tenantNamesStr)
                throw BizException(messageUtil.getMessage("error.user.is_tenant_admin_cannot_disable", *arrayOf(tenantNamesStr)))
            }
        }

        return sysUserMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteUser(id: Long): Boolean {
        log.info("Deleting user, id: {}", id)
        val user = sysUserMapper.selectById(id)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        // Do not allow deleting system admin users (isAdmin = 1)
        if (user.isAdmin == 1) {
            throw BizException(messageUtil.getMessage("error.user.cannot_delete_admin"))
        }

        // Check if this user is an admin of any tenant
        val userTenants = userTenantMapper.selectByUserId(id)
        val tenantAdminRoles = userTenants.filter { it.role == "admin" }

        if (tenantAdminRoles.isNotEmpty()) {
            // User is an admin of some tenant(s), not allowed to delete
            // Query all tenant names
            val tenantNames = tenantAdminRoles.mapNotNull { userTenant ->
                val tenant = tenantMapper.selectById(userTenant.tenantId)
                tenant?.name
            }

            val tenantNamesStr = tenantNames.joinToString(", ")
            log.warn("User is tenant admin, not allowed to delete, userId: {}, tenantNames: {}", id, tenantNamesStr)
            throw BizException(messageUtil.getMessage("error.user.is_tenant_admin", *arrayOf(tenantNamesStr)))
        }

        // User is not an admin of any tenant, remove from all tenants
        if (userTenants.isNotEmpty()) {
            log.info("User belongs to {} tenants, will be removed from all, userId: {}", userTenants.size, id)
            userTenants.forEach { userTenant ->
                userTenantMapper.deleteByUserIdAndTenantId(id, userTenant.tenantId)
            }
            log.info("User removed from all tenants, userId: {}", id)
        }

        // Physically delete user
        log.info("Executing physical delete user, userId: {}", id)
        return sysUserMapper.deleteById(id) > 0
    }

    override fun convertToResponse(sysUser: SysUser): SysUserResponse {
        // Query tenant count for this user
        val tenantCount = userTenantMapper.selectByUserId(sysUser.id).size

        return SysUserResponse(
            id = sysUser.id,
            username = sysUser.username,
            nickname = sysUser.nickname,
            email = sysUser.email,
            phone = sysUser.phone,
            gender = sysUser.gender,
            avatar = sysUser.avatar,
            status = sysUser.status,
            isAdmin = sysUser.isAdmin,
            lastLoginTime = sysUser.lastLoginTime,
            createTime = sysUser.createTime,
            updateTime = sysUser.updateTime,
            tenantCount = tenantCount,
        )
    }

    override fun getByUsername(username: String): SysUser? = sysUserMapper.selectByUsername(username)

    override fun existsByUsername(username: String): Boolean = sysUserMapper.selectByUsername(username) != null

    override fun existsByPhone(phone: String): Boolean = sysUserMapper.selectByPhone(phone) != null

    override fun existsByEmail(email: String): Boolean = sysUserMapper.selectByEmail(email) != null
}
