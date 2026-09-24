package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Session
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Session Mapper interface
 */
@Mapper
interface SessionMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): Session?

    fun insert(session: Session): Int

    fun updateById(session: Session): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================

    /**
     * Query list with conditions.
     *
     * Tenant-scoped list query: [tenantId] is pushed into the SQL when non-null, mirroring
     * `selectAgentList`.
     */
    fun selectSessionList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long? = null,
    ): List<Session>

    fun countByTitle(@Param("title") title: String): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    fun selectBySessionIdAndStatus(@Param("sessionId") sessionId: String, @Param("status") status: Int): Session?

    fun selectByAgentId(@Param("agentId") agentId: Long): List<Session>

    /**
     * Live sessions of one agent. `selectByAgentId` is capped at 10 rows because it only feeds the
     * response's session list, so it cannot answer whether an agent is still in use.
     */
    fun countByAgentId(@Param("agentId") agentId: Long): Int

    /** Of those, how many are still in progress (`status = 1`) rather than ended. */
    fun countRunningByAgentId(@Param("agentId") agentId: Long): Int

    /**
     * Active sessions running in team mode for this team.
     *
     * Deliberately unbounded: the caller pushes a refresh to every row it returns, so a cap would drop
     * conversations from the prompt while still reporting success.
     */
    fun selectByTeamId(@Param("teamId") teamId: Long): List<Session>
}
