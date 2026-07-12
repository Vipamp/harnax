package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentMcpBinding
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentMcpBindingMapper {

    fun selectByAgentId(@Param("agentId") agentId: Long): List<AgentMcpBinding>

    fun batchInsert(@Param("list") list: List<AgentMcpBinding>): Int

    fun deleteByAgentId(@Param("agentId") agentId: Long): Int
}
