package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolCallLogEntity
import org.apache.ibatis.annotations.Mapper

/**
 * ToolCallLogEntity Mapper interface.
 *
 * Append-only: a call log row is written once and never read back through this mapper, so there is no
 * select, update or delete here. `tool_call_log` is not cleaned up with its session or agent.
 */
@Mapper
interface ToolCallLogMapper {

    fun insert(toolcalllogentity: ToolCallLogEntity): Int
}
