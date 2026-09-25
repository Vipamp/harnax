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

    /** Batch read used by the team pages to name a lead and its members in one query. */
    fun selectByIds(@Param("ids") ids: List<Long>): List<Agent>

    /**
     * Exact-name lookup within one tenant, the pre-insert / pre-rename guard for
     * `uk_agent_tenant_active_name`. The tenant is mandatory: without it the lookup
     * matches another tenant's row and refuses a name that is free in this tenant.
     */
    fun selectByName(
        @Param("name") name: String,
        @Param("tenantId") tenantId: Long,
    ): Agent?

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
     * Agents of [tenantId] whose tool / MCP / CLI binding snapshot still carries this `envVarId`.
     *
     * A reference is the only thing a binding stores for a global variable, and delivery follows it
     * live, so deleting the variable silently empties every agent pointing at it.
     *
     * [tenantId] is a hard parameter with no default: the three binding tables carry no `tenant_id`
     * column, so the predicate belongs on the `agent` rows this answers with, and this project has no
     * MyBatis tenant interceptor - isolation exists only where a SQL statement states it. Without it
     * the delete refusal named another tenant's agents to the caller.
     */
    fun selectByEnvVarRef(
        @Param("envVarId") envVarId: Long,
        @Param("tenantId") tenantId: Long,
    ): List<Agent>
}
