package com.agnetix.harnax.router.mapper

import com.agnetix.harnax.router.entity.SessionMapping
import org.apache.ibatis.annotations.*

/**
 * Mapper for session mapping operations.
 */
@Mapper
interface SessionMappingMapper {

    @Insert(
        """
        INSERT INTO session_mapping (session_id, instance_id, agent_id, last_active_time, active)
        VALUES (#{sessionId}, #{instanceId}, #{agentId}, #{lastActiveTime}, #{active})
    """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(mapping: SessionMapping): Int

    @Select("SELECT * FROM session_mapping WHERE session_id = #{sessionId} AND active = 1")
    fun selectBySessionId(sessionId: String): SessionMapping?

    @Select("SELECT * FROM session_mapping WHERE instance_id = #{instanceId} AND active = 1")
    fun selectByInstanceId(instanceId: String): List<SessionMapping>

    @Update(
        """
        UPDATE session_mapping 
        SET instance_id = #{instanceId}, last_active_time = #{lastActiveTime}, update_time = NOW()
        WHERE session_id = #{sessionId} AND active = 1
    """,
    )
    fun updateBinding(@Param("sessionId") sessionId: String, @Param("instanceId") instanceId: String, @Param("lastActiveTime") lastActiveTime: java.time.LocalDateTime): Int

    @Update(
        """
        UPDATE session_mapping SET active = 0, update_time = NOW() 
        WHERE session_id = #{sessionId}
    """,
    )
    fun deleteBySessionId(sessionId: String): Int

    @Update(
        """
        UPDATE session_mapping SET active = 0, update_time = NOW() 
        WHERE instance_id = #{instanceId}
    """,
    )
    fun deleteByInstanceId(instanceId: String): Int

    @Update(
        """
        UPDATE session_mapping SET last_active_time = #{lastActiveTime}, update_time = NOW()
        WHERE session_id = #{sessionId} AND active = 1
    """,
    )
    fun refreshActiveTime(@Param("sessionId") sessionId: String, @Param("lastActiveTime") lastActiveTime: java.time.LocalDateTime): Int

    @Update(
        """
        UPDATE session_mapping SET instance_id = #{newInstanceId}, update_time = NOW()
        WHERE instance_id = #{oldInstanceId} AND active = 1
    """,
    )
    fun rebindSessions(@Param("oldInstanceId") oldInstanceId: String, @Param("newInstanceId") newInstanceId: String): Int
}
