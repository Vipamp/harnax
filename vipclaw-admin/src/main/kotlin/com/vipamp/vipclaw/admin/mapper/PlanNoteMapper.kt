package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.PlanNoteEntity
import org.apache.ibatis.annotations.*

/**
 * PlanNoteEntity Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface PlanNoteMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM plan_note_entity WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): PlanNoteEntity?

    @Insert(
        """
        INSERT INTO plan_note_entity (
            session_id, plan_id, name, description, expected_outcome, subtasks, created_at, cost_timeseconds, status, create_time
        ) VALUES (
            #{sessionId}, #{planId}, #{name}, #{description}, #{expectedOutcome}, #{subtasks}, #{createdAt}, #{costTimeseconds}, #{status}, #{createTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(plannoteentity: PlanNoteEntity): Int

    @Update(
        """
        UPDATE plan_note_entity SET
            session_id = #{sessionId},
            plan_id = #{planId},
            name = #{name},
            description = #{description},
            expected_outcome = #{expectedOutcome},
            subtasks = #{subtasks},
            created_at = #{createdAt},
            cost_timeseconds = #{costTimeseconds},
            status = #{status}
        WHERE id = #{id}
        """
    )
    fun updateById(plannoteentity: PlanNoteEntity): Int

    @Update("UPDATE plan_note_entity SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
@Select("SELECT * FROM plan_note WHERE session_id = #{sessionId} AND plan_id = #{planId} LIMIT 1")
    fun selectBySessionIdAndPlanId(
        @Param("sessionId") sessionId: String,
        @Param("planId") planId: String
    ): PlanNoteEntity?

    @Select("SELECT * FROM plan_note WHERE session_id = #{sessionId} ORDER BY create_time DESC")
    fun selectBySessionId(@Param("sessionId") sessionId: String): List<PlanNoteEntity>

    @Select("DELETE FROM plan_note WHERE session_id = #{sessionId}")
    fun deleteBySessionId(@Param("sessionId") sessionId: String): Int
}
