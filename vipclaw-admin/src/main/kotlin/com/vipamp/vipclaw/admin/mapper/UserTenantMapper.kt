package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.UserTenantEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 用户-租户关联 Mapper 接口
 *
 * @author vipamp
 * @since 2026-04-28
 */
@Mapper
interface UserTenantMapper {

    /**
     * 根据用户 ID 查询租户列表
     */
    fun selectByUserId(@Param("userId") userId: Long): List<UserTenantEntity>

    /**
     * 根据租户 ID 查询用户列表
     */
    fun selectByTenantId(@Param("tenantId") tenantId: Long): List<UserTenantEntity>

    /**
     * 根据用户 ID 和租户 ID 查询
     */
    fun selectByUserIdAndTenantId(
        @Param("userId") userId: Long,
        @Param("tenantId") tenantId: Long,
    ): UserTenantEntity?

    /**
     * 插入用户-租户关联
     */
    fun insert(userTenant: UserTenantEntity): Int

    /**
     * 根据用户 ID 和租户 ID 删除
     */
    fun deleteByUserIdAndTenantId(
        @Param("userId") userId: Long,
        @Param("tenantId") tenantId: Long,
    ): Int

    /**
     * 根据租户 ID 删除所有关联
     */
    fun deleteByTenantId(@Param("tenantId") tenantId: Long): Int

    /**
     * 更新用户角色
     */
    fun updateRole(
        @Param("userId") userId: Long,
        @Param("tenantId") tenantId: Long,
        @Param("role") role: String,
    ): Int
}
