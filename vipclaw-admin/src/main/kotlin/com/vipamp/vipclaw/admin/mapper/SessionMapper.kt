package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Session
import org.apache.ibatis.annotations.*

/**
 * 会话 Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-25
 */
@Mapper
interface SessionMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM session WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): Session?

    @Insert(
        """
        INSERT INTO session (
            session_id, title, session_description, agent_id, model_id,
            system_prompt, status, is_public, creator, active, create_time, update_time
        ) VALUES (
            #{sessionId}, #{title}, #{sessionDescription}, #{agentId}, #{modelId},
            #{systemPrompt}, #{status}, #{isPublic}, #{creator}, #{active}, #{createTime}, #{updateTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(session: Session): Int

    @Update(
        """
        UPDATE session SET
            title = #{title},
            session_description = #{sessionDescription},
            agent_id = #{agentId},
            model_id = #{modelId},
            system_prompt = #{systemPrompt},
            status = #{status},
            is_public = #{isPublic},
            active = #{active},
            update_time = #{updateTime}
        WHERE id = #{id}
        """
    )
    fun updateById(session: Session): Int

    @Update("UPDATE session SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================

    @Select("""
        <script>
            SELECT * FROM session 
            WHERE active = 1
            AND (is_public = 1 OR creator = #{currentUsername})
            <if test='keyword != null and keyword != ""'>
                AND title LIKE CONCAT('%', #{keyword}, '%')
            </if>
            <if test='status != null'>
                AND status = #{status}
            </if>
            ORDER BY create_time DESC
        </script>
    """)
    fun selectSessionList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String
    ): List<Session>

    @Select("SELECT COUNT(*) FROM session WHERE title = #{title} AND active = 1")
    fun countByTitle(@Param("title") title: String): Int

    @Select("SELECT * FROM session WHERE id = #{id} AND active = 1 LIMIT 1")
    fun selectActiveById(@Param("id") id: Long): Session?

    @Update("UPDATE session SET status = #{status}, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    @Select("SELECT * FROM session WHERE session_id = #{sessionId} AND status = #{status} LIMIT 1")
    fun selectBySessionIdAndStatus(@Param("sessionId") sessionId: String, @Param("status") status: Int): Session?

    @Select("""
        <script>
            SELECT * FROM session 
            WHERE agent_id = #{agentId} AND active = 1
            ORDER BY create_time DESC
            LIMIT 10
        </script>
    """)
    fun selectByAgentId(@Param("agentId") agentId: Long): List<Session>
}
