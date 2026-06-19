package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SysUserCreateRequest
import com.agnetix.harnax.admin.dto.SysUserResponse
import com.agnetix.harnax.admin.dto.SysUserUpdateRequest
import com.agnetix.harnax.entity.SysUser

/**
 * User service interface
 */
interface SysUserService {

    /**
     * Query user list with pagination
     *
     * @param keyword  Fuzzy search field
     * @param status   Status filter field
     * @param tenantId Tenant ID filter
     * @param pageNum  Current page number
     * @param pageSize Page size
     * @return Paginated result
     */
    fun page(keyword: String?, status: Int?, tenantId: Long?, pageNum: Int, pageSize: Int): Page<SysUser>

    /**
     * Get single user details
     *
     * @param id User ID
     * @return User entity
     */
    fun getSysUser(id: Long): SysUser?

    /**
     * Create user
     *
     * Email and phone are required and must be in valid format.
     *
     * @param request User create request object
     * @return Create result
     */
    fun createUser(request: SysUserCreateRequest): Boolean

    /**
     * Update user
     *
     * Email and phone are required and must be in valid format.
     *
     * @param id      User ID
     * @param request User update request object
     * @return Update result
     */
    fun updateUser(id: Long, request: SysUserUpdateRequest): Boolean

    /**
     * Toggle user enable status
     *
     * @param id     User ID
     * @param status Enable status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun toggleUserStatus(id: Long, status: Int): Boolean

    /**
     * Delete user
     *
     * @param id User ID
     * @return Delete result
     */
    fun deleteUser(id: Long): Boolean

    /**
     * Query user by username
     *
     * @param username Username
     * @return User entity
     */
    fun getByUsername(username: String): SysUser?

    /**
     * Check if username exists
     *
     * @param username Username
     * @return Whether exists
     */
    fun existsByUsername(username: String): Boolean

    /**
     * Check if phone exists
     *
     * @param phone Phone number
     * @return Whether exists
     */
    fun existsByPhone(phone: String): Boolean

    /**
     * Check if email exists
     *
     * @param email Email
     * @return Whether exists
     */
    fun existsByEmail(email: String): Boolean

    /**
     * Convert user entity to response object
     *
     * @param sysUser User entity
     * @return User response object
     */
    fun convertToResponse(sysUser: SysUser): SysUserResponse
}
