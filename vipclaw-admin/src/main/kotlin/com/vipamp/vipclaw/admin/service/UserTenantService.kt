package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.response.TenantResponse
import com.vipamp.vipclaw.admin.entity.UserTenantEntity

/**
 * User-tenant association service interface
 */
interface UserTenantService {

    /**
     * Get all tenants user belongs to
     */
    fun getUserTenants(userId: Long): List<TenantResponse>

    /**
     * Get user info in specified tenant
     */
    fun getUserTenantInfo(userId: Long, tenantId: Long): UserTenantEntity?

    /**
     * Check if user belongs to specified tenant
     */
    fun isUserInTenant(userId: Long, tenantId: Long): Boolean

    /**
     * Update user role in tenant
     */
    fun updateUserRole(userId: Long, tenantId: Long, role: String): Boolean

    /**
     * Get user list under tenant (paginated)
     */
    fun getUsersByTenantId(tenantId: Long, pageNum: Int, pageSize: Int): Any

    /**
     * Add user to tenant
     */
    fun addUserToTenant(tenantId: Long, userId: Long, role: String, operator: String): Boolean

    /**
     * Remove user from tenant
     */
    fun removeUserFromTenant(tenantId: Long, userId: Long, operator: String): Boolean
}
