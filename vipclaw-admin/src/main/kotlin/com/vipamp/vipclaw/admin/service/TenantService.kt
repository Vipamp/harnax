package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.request.CreateTenantRequest

import com.vipamp.vipclaw.admin.dto.response.TenantResponse
import com.vipamp.vipclaw.admin.dto.response.UserTenantResponse
import com.vipamp.vipclaw.common.page.Page

/**
 * 租户服务接口
 *
 * @author vipamp
 * @since 2026-04-28
 */
interface TenantService {

    /**
     * 创建租户
     */
    fun createTenant(request: CreateTenantRequest, creator: String): TenantResponse

    /**
     * 根据 ID 获取租户
     */
    fun getTenantById(id: Long): TenantResponse?

    /**
     * 分页查询租户列表
     */
    fun getTenantList(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<TenantResponse>



    /**
     * 切换租户状态
     */
    fun toggleStatus(id: Long): Boolean

    /**
     * 删除租户
     */
    fun deleteTenant(id: Long): Boolean

    /**
     * 查询租户下的用户列表
     */
    fun getTenantUsers(tenantId: Long, pageNum: Int, pageSize: Int): Page<UserTenantResponse>

    /**
     * 添加用户到租户
     */
    fun addUserToTenant(tenantId: Long, userId: Long, role: String): Boolean

    /**
     * 从租户移除用户
     */
    fun removeUserFromTenant(tenantId: Long, userId: Long): Boolean

    /**
     * 更新用户在租户中的角色
     */
    fun updateUserRole(tenantId: Long, userId: Long, role: String): Boolean
}
