package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentSkillBinding
import com.agnetix.harnax.entity.dto.SkillAgentBindingCount
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentSkillBindingMapper {

    fun selectByAgentId(@Param("agentId") agentId: Long): List<AgentSkillBinding>

    fun batchInsert(@Param("list") list: List<AgentSkillBinding>): Int

    fun deleteByAgentId(@Param("agentId") agentId: Long): Int

    fun deleteBySkillIds(@Param("skillIds") skillIds: List<Long>): Int

    /**
     * How many agents bind each of [skillIds]. A skill with no binding is absent from the answer.
     *
     * Grouped rather than counted one at a time: a page of skills asks this for every row on it, and
     * the same read is what the disable and delete guards consult.
     */
    fun selectAgentBindingCounts(@Param("skillIds") skillIds: List<Long>): List<SkillAgentBindingCount>
}
