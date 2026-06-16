package com.agnetix.harnax.router.mapper

import com.agnetix.harnax.router.entity.SessionMapping
import org.apache.ibatis.annotations.*

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

    @Insert(
        """
        INSERT INTO session_mapping (session_id, instance_id, agent_id, last_active_time, active, version)
        VALUES (#{sessionId}, #{instanceId}, #{agentId}, #{lastActiveTime}, 1, 0)
        ON DUPLICATE KEY UPDATE
            instance_id = VALUES(instance_id),
            agent_id = VALUES(agent_id),
            last_active_time = VALUES(last_active_time),
            active = 1,
            version = version + 1,
            update_time = NOW()
    """,
    )
    fun upsertBinding(@Param("sessionId") sessionId: String, @Param("instanceId") instanceId: String, @Param("agentId") agentId: Long?, @Param("lastActiveTime") lastActiveTime: java.time.LocalDateTime): Int

    @Update(
        """
        UPDATE session_mapping SET instance_id = #{newInstanceId}, version = version + 1, update_time = NOW()
        WHERE instance_id = #{oldInstanceId} AND active = 1
    """,
    )
    fun rebindSessions(@Param("oldInstanceId") oldInstanceId: String, @Param("newInstanceId") newInstanceId: String): Int

    @Select("SELECT COUNT(*) FROM session_mapping WHERE instance_id = #{instanceId} AND active = 1")
    fun countSessionsByInstance(@Param("instanceId") instanceId: String): Int

    @Select(
        """
        <script>
        SELECT instance_id, COUNT(*) as cnt FROM session_mapping 
        WHERE instance_id IN 
        <foreach item="id" collection="instanceIds" open="(" separator="," close=")">
            #{id}
        </foreach>
        AND active = 1 GROUP BY instance_id
        </script>
    """,
    )
    fun countSessionsByInstances(@Param("instanceIds") instanceIds: List<String>): List<Map<String, Any>>

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

    @Delete("DELETE FROM session_mapping WHERE active = 0 AND update_time < #{cutoffTime}")
    fun purgeDeletedMappings(@Param("cutoffTime") cutoffTime: java.time.LocalDateTime): Int
}
