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
     * Query list with conditions.
     *
     * Tenant-scoped list query: [tenantId] is pushed into the SQL when non-null, mirroring
     * `selectMcpServerList`.
     */
    fun selectAgentList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long? = null,
    ): List<Agent>

    /**
     * Agents whose tool / MCP / CLI binding snapshot still carries this `envVarId`.
     *
     * A reference is the only thing a binding stores for a global variable, and delivery follows it
     * live, so deleting the variable silently empties every agent pointing at it.
     */
    fun selectByEnvVarRef(@Param("envVarId") envVarId: Long): List<Agent>
}
