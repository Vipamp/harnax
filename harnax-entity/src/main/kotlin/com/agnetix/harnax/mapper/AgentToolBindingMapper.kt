package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentToolBinding
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentToolBindingMapper {

    fun selectByAgentId(@Param("agentId") agentId: Long): List<AgentToolBinding>

    fun batchInsert(@Param("list") list: List<AgentToolBinding>): Int

    fun deleteByAgentId(@Param("agentId") agentId: Long): Int

    /** Cascade clean when the builtin tool sync removes records the code no longer declares */
    fun deleteByToolIds(@Param("toolIds") toolIds: List<Long>): Int
}
