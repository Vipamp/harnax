package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentCliBinding
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentCliBindingMapper {

    fun selectByAgentId(@Param("agentId") agentId: Long): List<AgentCliBinding>

    /**
     * Every binding of every agent, in no particular order. The CLI inventory needs the whole table at
     * once: the reclaim sweep decides which sandbox images still have a reason to exist, and one lookup
     * per agent would only be the same answer fetched slower.
     */
    fun selectAll(): List<AgentCliBinding>

    /** Reverse lookup: which agents reference this CLI. */
    fun selectByCliId(@Param("cliId") cliId: Long): List<AgentCliBinding>

    fun batchInsert(@Param("list") list: List<AgentCliBinding>): Int

    fun deleteByAgentId(@Param("agentId") agentId: Long): Int

    /**
     * Cascade for a CLI that no longer exists: its package left the directory, so every agent that
     * referenced it has to stop doing so.
     */
    fun deleteByCliIds(@Param("cliIds") cliIds: List<Long>): Int
}
