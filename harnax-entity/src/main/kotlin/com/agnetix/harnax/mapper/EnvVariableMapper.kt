package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.EnvVariable
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface EnvVariableMapper {

    fun selectById(@Param("id") id: Long): EnvVariable?

    fun insert(envVariable: EnvVariable): Int

    fun updateById(envVariable: EnvVariable): Int

    fun deleteById(@Param("id") id: Long): Int

    fun toggleEnabled(@Param("id") id: Long, @Param("enabled") enabled: Int): Int

    /**
     * Variables of one tenant, further narrowed to the ones [currentUsername] created.
     *
     * [tenantId] is not covered by the creator filter and cannot be dropped: usernames carry no unique
     * index, so two accounts with the same login in different tenants saw each other's keys and
     * masked values.
     */
    fun selectEnvVariableList(
        @Param("keyword") keyword: String?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long,
    ): List<EnvVariable>
}
