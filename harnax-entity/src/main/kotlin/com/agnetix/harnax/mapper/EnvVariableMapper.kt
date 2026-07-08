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

    fun selectEnvVariableList(
        @Param("keyword") keyword: String?,
        @Param("currentUsername") currentUsername: String,
    ): List<EnvVariable>
}
