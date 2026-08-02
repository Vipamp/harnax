package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentSkillBinding
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentSkillBindingMapper {

    fun selectByAgentId(@Param("agentId") agentId: Long): List<AgentSkillBinding>

    fun batchInsert(@Param("list") list: List<AgentSkillBinding>): Int

    fun deleteByAgentId(@Param("agentId") agentId: Long): Int

    fun deleteBySkillIds(@Param("skillIds") skillIds: List<Long>): Int
}
