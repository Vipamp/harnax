package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.PlanNoteEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * PlanNoteEntity Mapper interface
 */
@Mapper
interface PlanNoteMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): PlanNoteEntity?

    fun insert(plannoteentity: PlanNoteEntity): Int

    fun updateById(plannoteentity: PlanNoteEntity): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================
    fun selectBySessionIdAndPlanId(
        @Param("sessionId") sessionId: String,
        @Param("planId") planId: String,
    ): PlanNoteEntity?

    fun selectBySessionId(@Param("sessionId") sessionId: String): List<PlanNoteEntity>

    fun deleteBySessionId(@Param("sessionId") sessionId: String): Int
}
