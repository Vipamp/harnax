package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentCliPluginBinding
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentCliPluginBindingMapper {

    fun selectByAgentId(@Param("agentId") agentId: Long): List<AgentCliPluginBinding>

    fun batchInsert(@Param("list") list: List<AgentCliPluginBinding>): Int

    fun deleteByAgentId(@Param("agentId") agentId: Long): Int
}
