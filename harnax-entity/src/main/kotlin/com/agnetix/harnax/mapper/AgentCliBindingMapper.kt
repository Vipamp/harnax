package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentCliBinding
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentCliBindingMapper {

    fun selectByAgentId(@Param("agentId") agentId: Long): List<AgentCliBinding>

    /** Reverse lookup: which agents reference this CLI. */
    fun selectByCliId(@Param("cliId") cliId: Long): List<AgentCliBinding>

    fun batchInsert(@Param("list") list: List<AgentCliBinding>): Int

    fun deleteByAgentId(@Param("agentId") agentId: Long): Int
}
