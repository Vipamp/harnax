package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.UserTenantEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * User-Tenant Association Mapper interface
 */
@Mapper
interface UserTenantMapper {

    /**
     * Query tenant list by user ID
     */
    fun selectByUserId(@Param("userId") userId: Long): List<UserTenantEntity>

    /**
     * Query user list by tenant ID
     */
    fun selectByTenantId(@Param("tenantId") tenantId: Long): List<UserTenantEntity>

    /**
     * Query by user ID and tenant ID
     */
    fun selectByUserIdAndTenantId(
        @Param("userId") userId: Long,
        @Param("tenantId") tenantId: Long,
    ): UserTenantEntity?

    /**
     * Insert user-tenant association
     */
    fun insert(userTenant: UserTenantEntity): Int

    /**
     * Delete by user ID and tenant ID
     */
    fun deleteByUserIdAndTenantId(
        @Param("userId") userId: Long,
        @Param("tenantId") tenantId: Long,
    ): Int

    /**
     * Delete all associations by tenant ID
     */
    fun deleteByTenantId(@Param("tenantId") tenantId: Long): Int

    /**
     * Update user role
     */
    fun updateRole(
        @Param("userId") userId: Long,
        @Param("tenantId") tenantId: Long,
        @Param("role") role: String,
    ): Int
}
