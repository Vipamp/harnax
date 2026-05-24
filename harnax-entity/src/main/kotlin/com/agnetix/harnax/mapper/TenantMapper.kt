package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.TenantEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Tenant Mapper interface
 */
@Mapper
interface TenantMapper {

    /**
     * Query tenant by ID
     */
    fun selectById(@Param("id") id: Long): TenantEntity?

    /**
     * Query tenant by name
     */
    fun selectByName(@Param("name") name: String): TenantEntity?

    /**
     * Query tenant list
     */
    fun selectList(@Param("name") name: String?, @Param("status") status: Int?): List<TenantEntity>

    /**
     * Insert tenant
     */
    fun insert(tenant: TenantEntity): Int

    /**
     * Update tenant by ID
     */
    fun updateById(tenant: TenantEntity): Int

    /**
     * Update tenant status
     */
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    /**
     * Delete tenant by ID (logical delete)
     */
    fun deleteById(@Param("id") id: Long): Int
}
