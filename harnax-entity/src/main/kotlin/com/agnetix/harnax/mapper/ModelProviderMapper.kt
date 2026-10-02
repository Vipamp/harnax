package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ModelProvider
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * ModelProvider Mapper interface
 */
@Mapper
interface ModelProviderMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): ModelProvider?

    fun insert(modelprovider: ModelProvider): Int

    fun updateById(modelprovider: ModelProvider): Int

    fun deleteById(@Param("id") id: Long): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    // ==================== Custom Query Methods ====================

    /**
     * The providers of [tenantId] that [currentUsername] may see: its own rows, and within them the
     * shared ones plus the private ones this user created.
     */
    fun selectModelProviderList(
        @Param("name") name: String?,
        @Param("type") type: String?,
        @Param("status") status: Int?,
        @Param("isPublic") isPublic: Int?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long,
    ): List<ModelProvider>

    fun countByName(@Param("name") name: String, @Param("tenantId") tenantId: Long): Int
}
