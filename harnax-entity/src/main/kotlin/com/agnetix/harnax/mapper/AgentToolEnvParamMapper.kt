package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentToolEnvParam
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentToolEnvParamMapper {

    fun selectByToolId(@Param("toolId") toolId: Long): List<AgentToolEnvParam>

    fun batchInsert(@Param("list") list: List<AgentToolEnvParam>): Int

    fun deleteByToolId(@Param("toolId") toolId: Long): Int

    fun selectById(@Param("id") id: Long): AgentToolEnvParam?

    fun insert(envParam: AgentToolEnvParam): Int

    fun updateById(envParam: AgentToolEnvParam): Int

    fun deleteById(@Param("id") id: Long): Int
}
