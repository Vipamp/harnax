package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentToolBinding
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentToolBindingMapper {

    fun selectByAgentId(@Param("agentId") agentId: Long): List<AgentToolBinding>

    fun batchInsert(@Param("list") list: List<AgentToolBinding>): Int

    fun deleteByAgentId(@Param("agentId") agentId: Long): Int
}
