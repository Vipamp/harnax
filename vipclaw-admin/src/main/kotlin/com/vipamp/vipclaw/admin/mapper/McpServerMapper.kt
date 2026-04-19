package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.McpServer
import org.apache.ibatis.annotations.*

/**
 * McpServer Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface McpServerMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM mcp_server WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): McpServer?

    @Insert(
        """
        INSERT INTO mcp_server (
            name, description, type, command, url, status, is_public, creator, active, create_time, update_time
        ) VALUES (
            #{name}, #{description}, #{type}, #{command}, #{url}, #{status}, #{isPublic}, #{creator}, #{active}, #{createTime}, #{updateTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(mcpserver: McpServer): Int

    @Update(
        """
        UPDATE mcp_server SET
            name = #{name},
            description = #{description},
            type = #{type},
            command = #{command},
            url = #{url},
            status = #{status},
            is_public = #{isPublic},
            creator = #{creator},
            active = #{active},
            update_time = #{updateTime}
        WHERE id = #{id}
        """
    )
    fun updateById(mcpserver: McpServer): Int

    @Update("UPDATE mcp_server SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    @Select(
        """
        <script>
            SELECT * FROM mcp_server 
            WHERE active = 1
            AND (is_public = 1 OR creator = #{currentUsername})
            <if test='keyword != null and keyword != ""'>
                AND (name LIKE CONCAT('%', #{keyword}, '%') 
                     OR description LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test='status != null'>
                AND status = #{status}
            </if>
            <if test='types != null and types != ""'>
                AND type IN
                <foreach item='type' index='index' collection='types.split(",")' open='(' separator=',' close=')'>
                    #{type}
                </foreach>
            </if>
            ORDER BY status DESC, update_time DESC
        </script>
    """
    )
    fun selectMcpServerList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("types") types: String?,
        @Param("currentUsername") currentUsername: String
    ): List<McpServer>

    @Select("SELECT * FROM mcp_server WHERE name = #{name} AND active = 1 LIMIT 1")
    fun selectByName(@Param("name") name: String): McpServer?

    @Select("SELECT * FROM mcp_server WHERE id = #{id} AND active = 1 LIMIT 1")
    fun selectActiveById(@Param("id") id: Long): McpServer?

    @Select("UPDATE mcp_server SET status = #{status}, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    @Select("UPDATE mcp_server SET active = 0, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun logicalDelete(@Param("id") id: Long): Int
}
