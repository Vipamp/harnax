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

    /**
     * 获取租户下的用户列表（分页）
     */
    fun getUsersByTenantId(tenantId: Long, pageNum: Int, pageSize: Int): Any

    /**
     * 添加用户到租户
     */
    fun addUserToTenant(tenantId: Long, userId: Long, role: String, operator: String): Boolean

    /**
     * 从租户移除用户
     */
    fun removeUserFromTenant(tenantId: Long, userId: Long, operator: String): Boolean
}
