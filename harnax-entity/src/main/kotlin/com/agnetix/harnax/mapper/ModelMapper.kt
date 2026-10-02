package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.dto.ModelUsage
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

    /**
     * The models of [tenantId] that [currentUsername] may see: its own rows, and within them the shared
     * ones plus the private ones this user created. `is_public` is a tenant-internal switch here, the
     * same reading `selectAgentList` gives it.
     */
    fun selectModelList(
        @Param("name") name: String?,
        @Param("providerId") providerId: Long?,
        @Param("modelType") modelType: String?,
        @Param("status") status: Int?,
        @Param("tags") tags: List<String>?,
        @Param("minPrice") minPrice: Double?,
        @Param("maxPrice") maxPrice: Double?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long,
    ): List<Model>

    /** Whether this tenant already names a model [name] under this provider. */
    fun countByProviderIdAndName(
        @Param("providerId") providerId: Long,
        @Param("name") name: String,
        @Param("tenantId") tenantId: Long,
    ): Int

    /** Whether this tenant already names a model [modelName] under this provider. */
    fun countByProviderIdAndModelName(
        @Param("providerId") providerId: Long,
        @Param("modelName") modelName: String,
        @Param("tenantId") tenantId: Long,
    ): Int

    fun countActiveModelsByProviderId(@Param("providerId") providerId: Long): Int

    fun countModelsByProviderId(@Param("providerId") providerId: Long): Int

    fun countDisabledModelsByProviderId(@Param("providerId") providerId: Long): Int

    /** Live agent / team / session rows that still run on this model, for the delete guard. */
    fun selectUsageByModelId(@Param("modelId") modelId: Long): ModelUsage
}
