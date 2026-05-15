package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.ModelProvider
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
    fun selectModelProviderList(
        @Param("name") name: String?,
        @Param("type") type: String?,
        @Param("status") status: Int?,
        @Param("isPublic") isPublic: Int?,
        @Param("currentUsername") currentUsername: String,
    ): List<ModelProvider>

    fun countByType(@Param("type") type: String): Int

    fun countByName(@Param("name") name: String): Int
}
