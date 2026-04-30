package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.TenantEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 租户 Mapper 接口
 *
 * @author vipamp
 * @since 2026-04-28
 */
@Mapper
interface TenantMapper {

    /**
     * 根据 ID 查询租户
     */
    fun selectById(@Param("id") id: Long): TenantEntity?

    /**
     * 根据名称查询租户
     */
    fun selectByName(@Param("name") name: String): TenantEntity?

    /**
     * 查询租户列表
     */
    fun selectList(@Param("name") name: String?, @Param("status") status: Int?): List<TenantEntity>

    /**
     * 插入租户
     */
    fun insert(tenant: TenantEntity): Int

    /**
     * 根据 ID 更新租户
     */
    fun updateById(tenant: TenantEntity): Int

    /**
     * 更新租户状态
     */
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    /**
     * 根据 ID 删除租户（逻辑删除）
     */
    fun deleteById(@Param("id") id: Long): Int
}
