package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Cli
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface CliMapper {

    fun selectById(@Param("id") id: Long): Cli?

    fun selectByIds(@Param("ids") ids: List<Long>): List<Cli>

    fun insert(cli: Cli): Int

    fun updateById(cli: Cli): Int

    fun deleteById(@Param("id") id: Long): Int

    fun selectCliList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long? = null,
    ): List<Cli>

    fun selectByName(@Param("name") name: String, @Param("tenantId") tenantId: Long): Cli?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int
}
