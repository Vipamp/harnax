package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.github.pagehelper.PageInfo
import com.vipamp.vipclaw.admin.dto.request.CreateTenantRequest

import com.vipamp.vipclaw.admin.dto.response.TenantResponse
import com.vipamp.vipclaw.admin.dto.response.UserTenantResponse
import com.vipamp.vipclaw.admin.entity.TenantEntity
import com.vipamp.vipclaw.admin.entity.UserTenantEntity
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.i18n.MessageUtil
import com.vipamp.vipclaw.admin.mapper.SysUserMapper
import com.vipamp.vipclaw.admin.mapper.TenantMapper
import com.vipamp.vipclaw.admin.mapper.UserTenantMapper
import com.vipamp.vipclaw.admin.service.TenantService
import com.vipamp.vipclaw.common.page.Page
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 租户服务实现类
 */
@Service
class TenantServiceImpl(
    private val tenantMapper: TenantMapper,
    private val userTenantMapper: UserTenantMapper,
    private val sysUserMapper: SysUserMapper,
    private val messageUtil: MessageUtil
) : TenantService {

    @Transactional
    override fun createTenant(request: CreateTenantRequest, creator: String): TenantResponse {
        // 检查租户名称是否已存在
        val existingTenant = tenantMapper.selectByName(request.name)
        if (existingTenant != null) {
            throw BizException(messageUtil.getMessage("error.tenant.name_exists"))
        }

        // 检查用户是否存在
        val adminUser = sysUserMapper.selectById(request.adminUserId)
        if (adminUser == null) {
            throw BizException(messageUtil.getMessage("error.user.notfound"))
        }

        // 创建租户
        val tenant = TenantEntity().apply {
            this.name = request.name
            this.status = 1
            this.creator = creator
            this.active = 1
            this.createTime = LocalDateTime.now()
            this.updateTime = LocalDateTime.now()
        }
        tenantMapper.insert(tenant)

        // 绑定管理员
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
            pageSize = pageInfo.pageSize.toLong()
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

        // TODO: 检查租户下是否有可用资源（agent、session、mcp、skill）
        // 第一期暂不实现

        // 删除租户关联
        userTenantMapper.deleteByTenantId(id)

        // 逻辑删除租户
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
                joinedAt = ut.joinedAt
            )
        }

        return Page(
            records = responses,
            total = pageInfo.total,
            pageNum = pageInfo.pageNum.toLong(),
            pageSize = pageInfo.pageSize.toLong()
        )
    }

    @Transactional
    override fun addUserToTenant(tenantId: Long, userId: Long, role: String): Boolean {
        // 检查租户是否存在
        tenantMapper.selectById(tenantId)
            ?: throw BizException(messageUtil.getMessage("error.tenant.notfound"))

        // 检查用户是否存在
        sysUserMapper.selectById(userId)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        // 检查是否已存在
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

        // 检查该用户是否是租户管理员
        if (existing.role == "admin") {
            // 查询该租户下的所有管理员
            val allUserTenants = userTenantMapper.selectByTenantId(tenantId)
            val adminCount = allUserTenants.count { it.role == "admin" && it.status == 1 }
            
            // 如果是唯一的管理员，不允许删除
            if (adminCount <= 1) {
                throw BizException(messageUtil.getMessage("error.tenant.cannot_remove_only_admin"))
            }
        }

        return userTenantMapper.deleteByUserIdAndTenantId(userId, tenantId) > 0
    }

    @Transactional
    override fun updateUserRole(tenantId: Long, userId: Long, role: String): Boolean {
        // 检查用户是否在租户中
        val existing = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
            ?: throw BizException(messageUtil.getMessage("error.user.not_in_tenant"))

        // 如果要降级为普通成员，检查是否是唯一管理员
        if (existing.role == "admin" && role != "admin") {
            val allUserTenants = userTenantMapper.selectByTenantId(tenantId)
            val adminCount = allUserTenants.count { it.role == "admin" && it.status == 1 }
            
            if (adminCount <= 1) {
                throw BizException(messageUtil.getMessage("error.tenant.cannot_demote_only_admin"))
            }
        }

        // 更新角色
        return userTenantMapper.updateRole(userId, tenantId, role) > 0
    }

    private fun toTenantResponse(entity: TenantEntity): TenantResponse {
        return TenantResponse(
            id = entity.id,
            name = entity.name,
            status = entity.status,
            creator = entity.creator,
            createTime = entity.createTime,
            updateTime = entity.updateTime
        )
    }
}
