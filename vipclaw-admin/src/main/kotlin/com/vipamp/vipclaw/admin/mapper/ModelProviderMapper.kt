package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.ModelProvider
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * ModelProvider Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface ModelProviderMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun selectById(@Param("id") id: Long): ModelProvider?

    fun insert(modelprovider: ModelProvider): Int

    fun updateById(modelprovider: ModelProvider): Int

    fun deleteById(@Param("id") id: Long): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    // ==================== 自定义查询方法 ====================
    fun selectModelProviderList(
        @Param("type") type: String?,
        @Param("status") status: Int?,
        @Param("isPublic") isPublic: Int?,
        @Param("currentUsername") currentUsername: String
    ): List<ModelProvider>

    fun countByType(@Param("type") type: String): Int

    fun countByName(@Param("name") name: String): Int
}
