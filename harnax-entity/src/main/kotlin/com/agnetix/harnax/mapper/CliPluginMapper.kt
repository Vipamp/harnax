package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.CliPlugin
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface CliPluginMapper {

    fun selectById(@Param("id") id: Long): CliPlugin?

    fun selectByName(@Param("name") name: String): CliPlugin?

    fun selectAll(): List<CliPlugin>

    fun selectByType(@Param("type") type: String): List<CliPlugin>

    fun selectAllEnabled(): List<CliPlugin>

    fun insert(cliPlugin: CliPlugin): Int

    fun updateById(cliPlugin: CliPlugin): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    /** Upsert a system CLI plugin (insert or update metadata on duplicate name) */
    fun upsertSystemPlugin(cliPlugin: CliPlugin): Int
}
