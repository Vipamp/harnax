package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.response.TenantResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.entity.UserTenantEntity
import com.agnetix.harnax.mapper.SysUserMapper
import com.agnetix.harnax.mapper.TenantMapper
import com.agnetix.harnax.mapper.UserTenantMapper
import com.github.pagehelper.PageHelper
import com.github.pagehelper.PageInfo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * User-tenant association service implementation
 */
@Service
class UserTenantServiceImpl(
    private val userTenantMapper: UserTenantMapper,
    private val tenantMapper: TenantMapper,
    private val sysUserMapper: SysUserMapper,
    private val messageUtil: MessageUtil,
) : UserTenantService {

    private val log = LoggerFactory.getLogger(UserTenantServiceImpl::class.java)

    override fun getUserTenants(userId: Long): List<TenantResponse> {
        val userTenants = userTenantMapper.selectByUserId(userId)

        return userTenants.mapNotNull { ut ->
            val tenant = tenantMapper.selectById(ut.tenantId)
            tenant?.let {
                TenantResponse(
                    id = it.id,
                    name = it.name,
                    status = it.status,
                    creator = it.creator,
                    createTime = it.createTime,
                    updateTime = it.updateTime,
                )
            }
        }
    }

    override fun getUserTenantInfo(userId: Long, tenantId: Long): UserTenantEntity? = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)

    override fun isUserInTenant(userId: Long, tenantId: Long): Boolean {
        val userTenant = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
        return userTenant != null && userTenant.status == 1
    }

    @Transactional
    override fun updateUserRole(userId: Long, tenantId: Long, role: String): Boolean {
        val existing = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
            ?: throw BizException(messageUtil.getMessage("error.user.not_in_tenant"))

        return userTenantMapper.updateRole(userId, tenantId, role) > 0
    }

    override fun getUsersByTenantId(tenantId: Long, pageNum: Int, pageSize: Int): Any {
        // Use PageHelper for pagination
        PageHelper.startPage<UserTenantEntity>(pageNum, pageSize)

        // Query user associations under tenant
        val userTenants = userTenantMapper.selectByTenantId(tenantId)
        val pageInfo = PageInfo(userTenants)

        // Assemble user information
        val records = userTenants.map { ut ->
            val user = sysUserMapper.selectById(ut.userId)
            mapOf(
                "userId" to ut.userId,
                "username" to (user?.username ?: ""),
                "nickname" to (user?.nickname ?: ""),
                "role" to ut.role,
                "status" to ut.status,
                "joinedAt" to ut.joinedAt,
            )
        }

        return mapOf(
            "records" to records,
            "total" to pageInfo.total,
            "pageNum" to pageInfo.pageNum,
            "pageSize" to pageInfo.pageSize,
        )
    }

    @Transactional
    override fun addUserToTenant(tenantId: Long, userId: Long, role: String, operator: String): Boolean {
        // Check if tenant exists
        val tenant = tenantMapper.selectById(tenantId)
            ?: throw BizException(messageUtil.getMessage("error.tenant.notfound"))

        // Check if user exists
        val user = sysUserMapper.selectById(userId)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        // Check if user is already in tenant
        val existing = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
        if (existing != null) {
            throw BizException(messageUtil.getMessage("error.user.already_in_tenant"))
        }

        // Add user to tenant
        val userTenant = UserTenantEntity().apply {
            this.userId = userId
            this.tenantId = tenantId
            this.role = role
            status = 1
            joinedAt = LocalDateTime.now()
        }
        return userTenantMapper.insert(userTenant) > 0
    }

    @Transactional
    override fun removeUserFromTenant(tenantId: Long, userId: Long, operator: String): Boolean {
        // Check if user is in tenant
        val existing = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
            ?: throw BizException(messageUtil.getMessage("error.user.not_in_tenant"))

        // Delete user-tenant association
        return userTenantMapper.deleteByUserIdAndTenantId(userId, tenantId) > 0
    }
}
