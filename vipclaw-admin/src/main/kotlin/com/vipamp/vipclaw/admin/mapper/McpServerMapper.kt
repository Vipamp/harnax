package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.McpServer
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * McpServer Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface McpServerMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun selectById(@Param("id") id: Long): McpServer?

    fun insert(mcpserver: McpServer): Int

    fun updateById(mcpserver: McpServer): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    fun selectMcpServerList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("types") types: String?,
        @Param("currentUsername") currentUsername: String
    ): List<McpServer>

    fun selectByName(@Param("name") name: String): McpServer?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int
}
