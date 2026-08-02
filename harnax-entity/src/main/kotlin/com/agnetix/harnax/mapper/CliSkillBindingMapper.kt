package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.CliSkillBinding
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface CliSkillBindingMapper {

    fun selectByCliId(@Param("cliId") cliId: Long): List<CliSkillBinding>

    fun selectByCliIds(@Param("cliIds") cliIds: List<Long>): List<CliSkillBinding>

    fun batchInsert(@Param("list") list: List<CliSkillBinding>): Int

    fun deleteByCliId(@Param("cliId") cliId: Long): Int

    fun deleteBySkillIds(@Param("skillIds") skillIds: List<Long>): Int
}
