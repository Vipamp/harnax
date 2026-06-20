package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SysUser
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/**
 * SysUser Mapper interface
 */
@Mapper
interface SysUserMapper {

    // ==================== Basic CRUD Methods ====================

    /**
     * Query user by ID
     *
     * @param id User ID
     * @return User entity
     */
    fun selectById(@Param("id") id: Long): SysUser?

    /**
     * Insert user
     *
     * @param sysuser User entity
     * @return Affected rows
     */
    fun insert(sysuser: SysUser): Int

    /**
     * Update user by ID
     *
     * @param sysuser User entity
     * @return Affected rows
     */
    fun updateById(sysuser: SysUser): Int

    /**
     * Delete user by ID (logical delete)
     *
     * @param id User ID
     * @return Affected rows
     */
    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================
    /**
     * Query user list with conditions
     *
     * @param keyword Keyword (search username/nickname/email/phone)
     * @param status User status
     * @param tenantId Tenant ID filter
     * @return User list
     */
    fun selectUserList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("tenantId") tenantId: Long?,
    ): List<SysUser>

    /**
     * Query by username
     *
     * @param username Username
     * @return User entity
     */
    fun selectByUsername(@Param("username") username: String): SysUser?

    /**
     * Query by phone
     *
     * @param phone Phone number
     * @return User entity
     */
    fun selectByPhone(@Param("phone") phone: String): SysUser?

    /**
     * Query by email
     *
     * @param email Email
     * @return User entity
     */
    fun selectByEmail(@Param("email") email: String): SysUser?

    /**
     * Update user status
     *
     * @param id User ID
     * @param status New status
     * @return Affected rows
     */
    fun updateStatus(
        @Param("id") id: Long,
        @Param("status") status: Int,
    ): Int

    /**
     * Update user's last login time
     *
     * @param id User ID
     * @param lastLoginTime Login time
     * @return Affected rows
     */
    fun updateLastLoginTime(
        @Param("id") id: Long,
        @Param("lastLoginTime") lastLoginTime: LocalDateTime,
    ): Int

    fun updatePassword(
        @Param("id") id: Long,
        @Param("password") password: String,
    ): Int
}
