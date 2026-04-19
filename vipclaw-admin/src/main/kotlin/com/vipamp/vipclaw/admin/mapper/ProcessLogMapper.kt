package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.ProcessLogEntity
import org.apache.ibatis.annotations.*

/**
 * ProcessLogEntity Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface ProcessLogMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM process_log_entity WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): ProcessLogEntity?

    @Insert(
        """
        INSERT INTO process_log_entity (
            agent_id, agent_name, session_id, message, log_type, stack_trace, ts
        ) VALUES (
            #{agentId}, #{agentName}, #{sessionId}, #{message}, #{logType}, #{stackTrace}, #{ts}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(processlogentity: ProcessLogEntity): Int

    @Update(
        """
        UPDATE process_log_entity SET
            agent_id = #{agentId},
            agent_name = #{agentName},
            session_id = #{sessionId},
            message = #{message},
            log_type = #{logType},
            stack_trace = #{stackTrace},
            ts = #{ts}
        WHERE id = #{id}
        """
    )
    fun updateById(processlogentity: ProcessLogEntity): Int

    @Update("UPDATE process_log_entity SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
}
