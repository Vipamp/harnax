package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentToolEnvParam
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * AgentToolEnvParam mapper.
 *
 * Reconciliation happens per row: the builtin tool registrar reads a tool's params with
 * [selectByToolId], then inserts, updates or deletes single rows. There is therefore no batch insert
 * and no delete-by-tool here.
 */
@Mapper
interface AgentToolEnvParamMapper {

    fun selectByToolId(@Param("toolId") toolId: Long): List<AgentToolEnvParam>

    fun insert(envParam: AgentToolEnvParam): Int

    fun updateById(envParam: AgentToolEnvParam): Int

    fun deleteById(@Param("id") id: Long): Int
}
