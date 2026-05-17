package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.request.CreateTenantRequest
import com.agnetix.harnax.admin.dto.response.TenantResponse
import com.agnetix.harnax.admin.dto.response.UserTenantResponse
import com.agnetix.harnax.admin.entity.TenantEntity
import com.agnetix.harnax.admin.entity.UserTenantEntity
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.mapper.SysUserMapper
import com.agnetix.harnax.admin.mapper.TenantMapper
import com.agnetix.harnax.admin.mapper.UserTenantMapper
import com.agnetix.harnax.admin.service.TenantService
import com.github.pagehelper.PageHelper
import com.github.pagehelper.PageInfo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Tenant service implementation
 */
@Service
class TenantServiceImpl(
    private val tenantMapper: TenantMapper,
    private val userTenantMapper: UserTenantMapper,
    private val sysUserMapper: SysUserMapper,
    private val messageUtil: MessageUtil,
) : TenantService {

    @Transactional
    override fun createTenant(request: CreateTenantRequest, creator: String): TenantResponse {
        // Check if tenant name already exists
        val existingTenant = tenantMapper.selectByName(request.name)
        if (existingTenant != null) {
            throw BizException(messageUtil.getMessage("error.tenant.name_exists"))
        }

        // Check if user exists
        val adminUser = sysUserMapper.selectById(request.adminUserId)
        if (adminUser == null) {
            throw BizException(messageUtil.getMessage("error.user.notfound"))
        }

        // Create tenant
        val tenant = TenantEntity().apply {
            this.name = request.name
            this.status = 1
            this.creator = creator
            this.active = 1
            this.createTime = LocalDateTime.now()
            this.updateTime = LocalDateTime.now()
        }
        tenantMapper.insert(tenant)

        // Bind admin
        val userTenant = UserTenantEntity().apply {
            this.userId = request.adminUserId
            this.tenantId = tenant.id
            this.role = "admin"
            this.status = 1
            this.joinedAt = LocalDateTime.now()
        }
        userTenantMapper.insert(userTenant)

        return toTenantResponse(tenant)
    }

    override fun getTenantById(id: Long): TenantResponse? {
        val tenant = tenantMapper.selectById(id)
        return tenant?.let { toTenantResponse(it) }
    }

    override fun getTenantList(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<TenantResponse> {
        PageHelper.startPage<TenantEntity>(pageNum, pageSize)
        val tenants = tenantMapper.selectList(name, status)
        val pageInfo = PageInfo(tenants)

        return Page(
            records = pageInfo.list.map { toTenantResponse(it) },
            total = pageInfo.total,
            pageNum = pageInfo.pageNum.toLong(),
            pageSize = pageInfo.pageSize.toLong(),
        )
    }

    @Transactional
    override fun toggleStatus(id: Long): Boolean {
        val tenant = tenantMapper.selectById(id)
            ?: throw BizException(messageUtil.getMessage("error.tenant.notfound"))

        val newStatus = if (tenant.status == 1) 0 else 1
        return tenantMapper.updateStatus(id, newStatus) > 0
    }

    @Transactional
    override fun deleteTenant(id: Long): Boolean {
        val tenant = tenantMapper.selectById(id)
            ?: throw BizException(messageUtil.getMessage("error.tenant.notfound"))

        // TODO: Check if tenant has available resources (agent, session, mcp, skill)
        // Not implemented in phase 1

        // Delete tenant associations
        userTenantMapper.deleteByTenantId(id)

        // Logical delete tenant
        return tenantMapper.deleteById(id) > 0
    }

    override fun getTenantUsers(tenantId: Long, pageNum: Int, pageSize: Int): Page<UserTenantResponse> {
        PageHelper.startPage<UserTenantEntity>(pageNum, pageSize)
        val userTenants = userTenantMapper.selectByTenantId(tenantId)
        val pageInfo = PageInfo(userTenants)

        val responses = pageInfo.list.map { ut ->
            val user = sysUserMapper.selectById(ut.userId)
            UserTenantResponse(
                id = ut.id,
                userId = ut.userId,
                username = user?.username,
                nickname = user?.nickname,
                tenantId = ut.tenantId,
                role = ut.role,
                status = ut.status,
                joinedAt = ut.joinedAt,
            )
        }

        return Page(
            records = responses,
            total = pageInfo.total,
            pageNum = pageInfo.pageNum.toLong(),
            pageSize = pageInfo.pageSize.toLong(),
        )
    }

    @Transactional
    override fun addUserToTenant(tenantId: Long, userId: Long, role: String): Boolean {
        // Check if tenant exists
        tenantMapper.selectById(tenantId)
            ?: throw BizException(messageUtil.getMessage("error.tenant.notfound"))

        // Check if user exists
        sysUserMapper.selectById(userId)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        // Check if already exists
        val existing = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
        if (existing != null) {
            throw BizException(messageUtil.getMessage("error.user.already_in_tenant"))
        }

        val userTenant = UserTenantEntity().apply {
            this.userId = userId
            this.tenantId = tenantId
            this.role = role
            this.status = 1
            this.joinedAt = LocalDateTime.now()
        }

        return userTenantMapper.insert(userTenant) > 0
    }

    @Transactional
    override fun removeUserFromTenant(tenantId: Long, userId: Long): Boolean {
        val existing = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
            ?: throw BizException(messageUtil.getMessage("error.user.not_in_tenant"))

        // Check if this user is a tenant admin
        if (existing.role == "admin") {
            // Query all admins under this tenant
            val allUserTenants = userTenantMapper.selectByTenantId(tenantId)
            val adminCount = allUserTenants.count { it.role == "admin" && it.status == 1 }

            // If it's the only admin, do not allow deletion
            if (adminCount <= 1) {
                throw BizException(messageUtil.getMessage("error.tenant.cannot_remove_only_admin"))
            }
        }

        return userTenantMapper.deleteByUserIdAndTenantId(userId, tenantId) > 0
    }

    @Transactional
    override fun updateUserRole(tenantId: Long, userId: Long, role: String): Boolean {
        // Check if user is in tenant
        val existing = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
            ?: throw BizException(messageUtil.getMessage("error.user.not_in_tenant"))

        // If downgrading to regular member, check if it's the only admin
        if (existing.role == "admin" && role != "admin") {
            val allUserTenants = userTenantMapper.selectByTenantId(tenantId)
            val adminCount = allUserTenants.count { it.role == "admin" && it.status == 1 }

            if (adminCount <= 1) {
                throw BizException(messageUtil.getMessage("error.tenant.cannot_demote_only_admin"))
            }
        }

        // Update role
        return userTenantMapper.updateRole(userId, tenantId, role) > 0
    }

    private fun toTenantResponse(entity: TenantEntity): TenantResponse = TenantResponse(
        id = entity.id,
        name = entity.name,
        status = entity.status,
        creator = entity.creator,
        createTime = entity.createTime,
        updateTime = entity.updateTime,
    )
}
