package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Agent
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Agent Mapper interface
 * SQL configuration in resources/mapper/AgentMapper.xml
 */
@Mapper
interface AgentMapper {

    /**
     * Query by ID
     */
    fun selectById(@Param("id") id: Long): Agent?

    /**
     * Insert
     */
    fun insert(agent: Agent): Int

    /**
     * Update
     */
    fun updateById(agent: Agent): Int

    /**
     * Logical delete
     */
    fun deleteById(@Param("id") id: Long): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    /**
     * Query list with conditions
     */
    fun selectAgentList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
    ): List<Agent>
}
