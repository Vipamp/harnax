package com.agnetix.harnax.router.mapper

import com.agnetix.harnax.router.dto.ApiCallLogQuery
import com.agnetix.harnax.router.entity.ApiCallLog
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param

/**
 * Mapper for the `api_call_log` table.
 *
 * INSERT statements stay as annotations because they are straightforward.
 * The dynamic query/count SQL lives in `mapper/ApiCallLogMapper.xml` so that
 * the conditional WHERE clause is readable and easy to extend without Kotlin
 * string concatenation.
 */
@Mapper
interface ApiCallLogMapper {

    @Insert(
        """
        INSERT INTO api_call_log (caller_id, caller_type, tenant_id, session_id, agent_id, agent_name,
            model_id, model_name, endpoint, method, request_type, status_code, success, error_message,
            start_time, end_time, duration_ms, instance_id, request_id)
        VALUES (#{callerId}, #{callerType}, #{tenantId}, #{sessionId}, #{agentId}, #{agentName},
            #{modelId}, #{modelName}, #{endpoint}, #{method}, #{requestType}, #{statusCode}, #{success}, #{errorMessage},
            #{startTime}, #{endTime}, #{durationMs}, #{instanceId}, #{requestId})
    """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(log: ApiCallLog): Int

    @Insert(
        """
        <script>
        INSERT INTO api_call_log (caller_id, caller_type, tenant_id, session_id, agent_id, agent_name,
            model_id, model_name, endpoint, method, request_type, status_code, success, error_message,
            start_time, end_time, duration_ms, instance_id, request_id)
        VALUES
        <foreach collection="logs" item="log" separator=",">
            (#{log.callerId}, #{log.callerType}, #{log.tenantId}, #{log.sessionId}, #{log.agentId}, #{log.agentName},
             #{log.modelId}, #{log.modelName}, #{log.endpoint}, #{log.method}, #{log.requestType}, #{log.statusCode}, #{log.success}, #{log.errorMessage},
             #{log.startTime}, #{log.endTime}, #{log.durationMs}, #{log.instanceId}, #{log.requestId})
        </foreach>
        </script>
    """,
    )
    fun batchInsert(@Param("logs") logs: List<ApiCallLog>): Int

    /**
     * Query call logs with optional filters. Returns rows ordered by start_time DESC.
     * SQL defined in mapper/ApiCallLogMapper.xml (id="query").
     */
    fun query(@Param("q") query: ApiCallLogQuery): List<ApiCallLog>

    /**
     * Count total rows matching the same filter (for pagination metadata).
     * SQL defined in mapper/ApiCallLogMapper.xml (id="count").
     */
    fun count(@Param("q") query: ApiCallLogQuery): Long
}
