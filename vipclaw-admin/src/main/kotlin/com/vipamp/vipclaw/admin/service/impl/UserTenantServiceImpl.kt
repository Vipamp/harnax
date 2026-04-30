package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.dto.response.TenantResponse
import com.vipamp.vipclaw.admin.entity.UserTenantEntity
import com.vipamp.vipclaw.admin.mapper.TenantMapper
import com.vipamp.vipclaw.admin.mapper.UserTenantMapper
import com.vipamp.vipclaw.admin.service.UserTenantService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 用户-租户关联服务实现类
 */
@Service
class UserTenantServiceImpl(
    private val userTenantMapper: UserTenantMapper,
    private val tenantMapper: TenantMapper
) : UserTenantService {

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
            ?: throw BizException("用户不在该租户中")

        return userTenantMapper.updateRole(userId, tenantId, role) > 0
    }
}
