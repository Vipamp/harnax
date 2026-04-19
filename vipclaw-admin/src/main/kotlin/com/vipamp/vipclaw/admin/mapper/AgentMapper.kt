package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Agent
import org.apache.ibatis.annotations.*

/**
 * 智能体 Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface AgentMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM agent WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): Agent?

    @Insert(
        """
        INSERT INTO agent (
            name, description, system_prompt, model_id, mcp_list, skill_list,
            owner, status, is_public, creator, active, create_time, update_time
        ) VALUES (
            #{name}, #{description}, #{systemPrompt}, #{modelId}, #{mcpList}, #{skillList},
            #{owner}, #{status}, #{isPublic}, #{creator}, #{active}, #{createTime}, #{updateTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(agent: Agent): Int

    @Update(
        """
        UPDATE agent SET
            name = #{name},
            description = #{description},
            system_prompt = #{systemPrompt},
            model_id = #{modelId},
            mcp_list = #{mcpList},
            skill_list = #{skillList},
            owner = #{owner},
            status = #{status},
            is_public = #{isPublic},
            active = #{active},
            update_time = #{updateTime}
        WHERE id = #{id}
        """
    )
    fun updateById(agent: Agent): Int

    @Update("UPDATE agent SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================

    @Select("""
        <script>
            SELECT * FROM agent 
            WHERE active = 1
            AND (is_public = 1 OR creator = #{currentUsername})
            <if test='name != null and name != ""'>
                AND name LIKE CONCAT('%', #{name}, '%')
            </if>
            <if test='status != null'>
                AND status = #{status}
            </if>
            ORDER BY create_time DESC
        </script>
    """)
    fun selectAgentList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String
    ): List<Agent>

    @Select("SELECT * FROM agent WHERE id = #{id} AND active = 1 LIMIT 1")
    fun selectActiveById(@Param("id") id: Long): Agent?
}
