package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.github.pagehelper.PageInfo
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.i18n.MessageUtil
import com.vipamp.vipclaw.admin.dto.response.TenantResponse
import com.vipamp.vipclaw.admin.entity.UserTenantEntity
import com.vipamp.vipclaw.admin.mapper.SysUserMapper
import com.vipamp.vipclaw.admin.mapper.TenantMapper
import com.vipamp.vipclaw.admin.mapper.UserTenantMapper
import com.vipamp.vipclaw.admin.service.UserTenantService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 用户-租户关联服务实现类
 */
@Service
class UserTenantServiceImpl(
    private val userTenantMapper: UserTenantMapper,
    private val tenantMapper: TenantMapper,
    private val sysUserMapper: SysUserMapper,
    private val messageUtil: MessageUtil
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
                    updateTime = it.updateTime
                )
            }
        }
    }

    override fun getUserTenantInfo(userId: Long, tenantId: Long): UserTenantEntity? {
        return userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
    }

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
        // 使用PageHelper进行分页
        PageHelper.startPage(pageNum, pageSize)
        
        // 查询租户下的用户关联
        val userTenants = userTenantMapper.selectByTenantId(tenantId)
        val pageInfo = PageInfo(userTenants)
        
        // 组装用户信息
        val records = userTenants.map { ut ->
            val user = sysUserMapper.selectById(ut.userId)
            mapOf(
                "userId" to ut.userId,
                "username" to (user?.username ?: ""),
                "nickname" to (user?.nickname ?: ""),
                "role" to ut.role,
                "status" to ut.status,
                "joinedAt" to ut.joinedAt
            )
        }
        
        return mapOf(
            "records" to records,
            "total" to pageInfo.total,
            "pageNum" to pageInfo.pageNum,
            "pageSize" to pageInfo.pageSize
        )
    }

    @Transactional
    override fun addUserToTenant(tenantId: Long, userId: Long, role: String, operator: String): Boolean {
        // 检查租户是否存在
        val tenant = tenantMapper.selectById(tenantId)
            ?: throw BizException(messageUtil.getMessage("error.tenant.notfound"))

        // 检查用户是否存在
        val user = sysUserMapper.selectById(userId)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        // 检查用户是否已经在租户中
        val existing = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
        if (existing != null) {
            throw BizException(messageUtil.getMessage("error.user.already_in_tenant"))
        }

        // 添加用户到租户
        val userTenant = UserTenantEntity(
            userId = userId,
            tenantId = tenantId,
            role = role,
            status = 1,
            joinedAt = LocalDateTime.now()
        )
        
        return userTenantMapper.insert(userTenant) > 0
    }

    @Transactional
    override fun removeUserFromTenant(tenantId: Long, userId: Long, operator: String): Boolean {
        // 检查用户是否在租户中
        val existing = userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)
            ?: throw BizException(messageUtil.getMessage("error.user.not_in_tenant"))

        // 删除用户租户关联
        return userTenantMapper.deleteByUserIdAndTenantId(userId, tenantId) > 0
    }
}
