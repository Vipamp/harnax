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

    fun selectSessionList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
    ): List<Session>

    fun countByTitle(@Param("title") title: String): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    fun selectBySessionIdAndStatus(@Param("sessionId") sessionId: String, @Param("status") status: Int): Session?

    fun selectByAgentId(@Param("agentId") agentId: Long): List<Session>
}
