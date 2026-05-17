package com.agnetix.harnax.admin.mapper

import com.agnetix.harnax.admin.entity.McpServer
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * McpServer Mapper interface
 */
@Mapper
interface McpServerMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): McpServer?

    fun insert(mcpserver: McpServer): Int

    fun updateById(mcpserver: McpServer): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================
    fun selectMcpServerList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("type") type: String?,
        @Param("currentUsername") currentUsername: String,
    ): List<McpServer>

    fun selectByName(@Param("name") name: String): McpServer?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int
}
