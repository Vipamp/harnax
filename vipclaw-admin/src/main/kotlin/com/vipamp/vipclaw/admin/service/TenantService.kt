package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.request.CreateTenantRequest
import com.vipamp.vipclaw.admin.dto.response.TenantResponse
import com.vipamp.vipclaw.admin.dto.response.UserTenantResponse

/**
 * Tenant service interface
 */
interface TenantService {

    /**
     * Create tenant
     */
    fun createTenant(request: CreateTenantRequest, creator: String): TenantResponse

    /**
     * Get tenant by ID
     */
    fun getTenantById(id: Long): TenantResponse?

    /**
     * Query tenant list with pagination
     */
    fun getTenantList(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<TenantResponse>

    /**
     * Toggle tenant status
     */
    fun toggleStatus(id: Long): Boolean

    /**
     * Delete tenant
     */
    fun deleteTenant(id: Long): Boolean

    /**
     * Query user list under tenant
     */
    fun getTenantUsers(tenantId: Long, pageNum: Int, pageSize: Int): Page<UserTenantResponse>

    /**
     * Add user to tenant
     */
    fun addUserToTenant(tenantId: Long, userId: Long, role: String): Boolean

    /**
     * Remove user from tenant
     */
    fun removeUserFromTenant(tenantId: Long, userId: Long): Boolean

    /**
     * Update user role in tenant
     */
    fun updateUserRole(tenantId: Long, userId: Long, role: String): Boolean
}
