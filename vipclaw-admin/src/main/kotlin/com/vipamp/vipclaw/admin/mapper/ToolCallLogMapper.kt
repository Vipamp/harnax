package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.ToolCallLogEntity
import org.apache.ibatis.annotations.*

/**
 * ToolCallLogEntity Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface ToolCallLogMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM tool_call_log_entity WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): ToolCallLogEntity?

    @Insert(
        """
        INSERT INTO tool_call_log_entity (
            agent_id, session_id, tool_name, args, result, success, start_time, end_time, duration, ts
        ) VALUES (
            #{agentId}, #{sessionId}, #{toolName}, #{args}, #{result}, #{success}, #{startTime}, #{endTime}, #{duration}, #{ts}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(toolcalllogentity: ToolCallLogEntity): Int

    @Update(
        """
        UPDATE tool_call_log_entity SET
            agent_id = #{agentId},
            session_id = #{sessionId},
            tool_name = #{toolName},
            args = #{args},
            result = #{result},
            success = #{success},
            start_time = #{startTime},
            end_time = #{endTime},
            duration = #{duration},
            ts = #{ts}
        WHERE id = #{id}
        """
    )
    fun updateById(toolcalllogentity: ToolCallLogEntity): Int

    @Update("UPDATE tool_call_log_entity SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
}
