package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.response.TenantResponse
import com.vipamp.vipclaw.admin.entity.UserTenantEntity

/**
 * 用户-租户关联服务接口
 *
 * @author vipamp
 * @since 2026-04-28
 */
interface UserTenantService {

    /**
     * 获取用户所属的所有租户
     */
    fun getUserTenants(userId: Long): List<TenantResponse>

    /**
     * 获取用户在指定租户的信息
     */
    fun getUserTenantInfo(userId: Long, tenantId: Long): UserTenantEntity?

    /**
     * 检查用户是否属于指定租户
     */
    fun isUserInTenant(userId: Long, tenantId: Long): Boolean

    /**
     * 更新用户在租户中的角色
     */
    fun updateUserRole(userId: Long, tenantId: Long, role: String): Boolean
}
