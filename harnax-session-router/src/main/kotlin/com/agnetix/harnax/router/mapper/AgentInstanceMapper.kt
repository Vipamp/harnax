package com.agnetix.harnax.router.mapper

import com.agnetix.harnax.router.entity.AgentInstance
import org.apache.ibatis.annotations.*

/**
 * Mapper for agent instance operations.
 */
@Mapper
interface AgentInstanceMapper {

    @Insert(
        """
        INSERT INTO agent_instance (instance_id, host, port, status, last_heartbeat, active)
        VALUES (#{instanceId}, #{host}, #{port}, #{status}, #{lastHeartbeat}, #{active})
    """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(instance: AgentInstance): Int

    @Insert(
        """
        INSERT INTO agent_instance (instance_id, host, port, status, last_heartbeat, active)
        VALUES (#{instanceId}, #{host}, #{port}, 'UP', #{lastHeartbeat}, 1)
        ON DUPLICATE KEY UPDATE
            host = VALUES(host),
            port = VALUES(port),
            status = 'UP',
            last_heartbeat = VALUES(last_heartbeat),
            active = 1,
            update_time = NOW()
    """,
    )
    fun upsertInstance(@Param("instanceId") instanceId: String, @Param("host") host: String, @Param("port") port: Int, @Param("lastHeartbeat") lastHeartbeat: java.time.LocalDateTime): Int

    @Select("SELECT * FROM agent_instance WHERE instance_id = #{instanceId} AND active = 1")
    fun selectByInstanceId(instanceId: String): AgentInstance?

    @Select("SELECT * FROM agent_instance WHERE status = 'UP' AND active = 1")
    fun selectHealthyInstances(): List<AgentInstance>

    @Select("SELECT * FROM agent_instance WHERE active = 1")
    fun selectAllInstances(): List<AgentInstance>

    @Update(
        """
        UPDATE agent_instance 
        SET last_heartbeat = #{lastHeartbeat}, status = #{status}, update_time = NOW()
        WHERE instance_id = #{instanceId} AND active = 1
    """,
    )
    fun updateHeartbeat(@Param("instanceId") instanceId: String, @Param("lastHeartbeat") lastHeartbeat: java.time.LocalDateTime, @Param("status") status: String): Int

    @Update(
        """
        UPDATE agent_instance SET status = 'DOWN', update_time = NOW() 
        WHERE instance_id = #{instanceId} AND active = 1 AND status IN ('UP', 'DRAINING')
    """,
    )
    fun markAsDown(instanceId: String): Int

    @Update(
        """
        UPDATE agent_instance SET active = 0, update_time = NOW() 
        WHERE instance_id = #{instanceId}
    """,
    )
    fun deleteByInstanceId(instanceId: String): Int

    @Delete("DELETE FROM agent_instance WHERE active = 0 AND update_time < #{cutoffTime}")
    fun purgeDeletedInstances(@Param("cutoffTime") cutoffTime: java.time.LocalDateTime): Int
}
