package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolCallLogEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * ToolCallLogEntity Mapper interface
 */
@Mapper
interface ToolCallLogMapper {

    // ==================== Basic CRUD methods ====================

    fun selectById(@Param("id") id: Long): ToolCallLogEntity?

    fun insert(toolcalllogentity: ToolCallLogEntity): Int

    fun updateById(toolcalllogentity: ToolCallLogEntity): Int

    fun deleteById(@Param("id") id: Long): Int
    fun selectBySessionId(@Param("sessionId") sessionId: String): List<ToolCallLogEntity>
    fun selectByToolName(@Param("toolName") toolName: String): List<ToolCallLogEntity>

    // ==================== Custom query methods ====================
}
