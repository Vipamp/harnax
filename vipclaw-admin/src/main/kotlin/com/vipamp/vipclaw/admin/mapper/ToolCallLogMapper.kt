package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.ToolCallLogEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * ToolCallLogEntity Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface ToolCallLogMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun selectById(@Param("id") id: Long): ToolCallLogEntity?

    fun insert(toolcalllogentity: ToolCallLogEntity): Int

    fun updateById(toolcalllogentity: ToolCallLogEntity): Int

    fun deleteById(@Param("id") id: Long): Int
    fun selectBySessionId(@Param("sessionId") sessionId: String): List<ToolCallLogEntity>
    fun selectByToolName(@Param("toolName") toolName: String): List<ToolCallLogEntity>

    // ==================== 自定义查询方法 ====================
}
