package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Session
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 会话 Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-25
 */
@Mapper
interface SessionMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun selectById(@Param("id") id: Long): Session?

    fun insert(session: Session): Int

    fun updateById(session: Session): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================

    fun selectSessionList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String
    ): List<Session>

    fun countByTitle(@Param("title") title: String): Int

    fun selectActiveById(@Param("id") id: Long): Session?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    fun selectBySessionIdAndStatus(@Param("sessionId") sessionId: String, @Param("status") status: Int): Session?

    fun selectByAgentId(@Param("agentId") agentId: Long): List<Session>
}
