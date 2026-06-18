package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ApiKeyEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface ApiKeyMapper {

    fun selectById(@Param("id") id: Long): ApiKeyEntity?

    fun selectByKeyHash(@Param("keyHash") keyHash: String): ApiKeyEntity?

    fun selectByName(@Param("name") name: String): ApiKeyEntity?

    fun insert(entity: ApiKeyEntity): Int

    fun updateById(entity: ApiKeyEntity): Int

    fun deleteById(@Param("id") id: Long): Int

    fun updateEnabled(@Param("id") id: Long, @Param("enabled") enabled: Int): Int

    fun selectApiKeyList(
        @Param("keyword") keyword: String?,
        @Param("enabled") enabled: Int?,
        @Param("creator") creator: String?,
        @Param("tenantId") tenantId: Long?,
    ): List<ApiKeyEntity>

    fun selectAllEnabled(): List<ApiKeyEntity>
}
