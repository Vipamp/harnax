package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Model
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Model Mapper interface
 */
@Mapper
interface ModelMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): Model?

    fun insert(model: Model): Int

    fun updateById(model: Model): Int

    fun deleteById(@Param("id") id: Long): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    // ==================== Custom Query Methods ====================
    fun selectModelList(
        @Param("name") name: String?,
        @Param("providerId") providerId: Long?,
        @Param("modelType") modelType: String?,
        @Param("status") status: Int?,
        @Param("tags") tags: List<String>?,
        @Param("minPrice") minPrice: Double?,
        @Param("maxPrice") maxPrice: Double?,
        @Param("currentUsername") currentUsername: String,
    ): List<Model>

    fun countByProviderIdAndName(@Param("providerId") providerId: Long, @Param("name") name: String): Int

    fun countByProviderIdAndModelName(@Param("providerId") providerId: Long, @Param("modelName") modelName: String): Int

    fun countActiveModelsByProviderId(@Param("providerId") providerId: Long): Int

    fun countModelsByProviderId(@Param("providerId") providerId: Long): Int

    fun countDisabledModelsByProviderId(@Param("providerId") providerId: Long): Int
}
