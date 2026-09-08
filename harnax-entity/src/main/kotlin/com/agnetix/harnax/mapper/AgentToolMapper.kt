package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTool
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentToolMapper {

    fun selectById(@Param("id") id: Long): AgentTool?

    /** Batch load undeleted tools by id (spec delivery resolves all bound + required tools at once) */
    fun selectByIds(@Param("ids") ids: List<Long>): List<AgentTool>

    fun insert(agentTool: AgentTool): Int

    fun updateById(agentTool: AgentTool): Int

    fun deleteById(@Param("id") id: Long): Int

    fun selectAgentToolList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("type") type: String?,
        @Param("currentUsername") currentUsername: String,
    ): List<AgentTool>

    fun selectByName(@Param("name") name: String): AgentTool?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    fun selectAllEnabled(): List<AgentTool>

    fun selectBuiltinToolList(): List<AgentTool>

    fun selectAvailableToolsByType(@Param("type") type: String?): List<AgentTool>

    /** Required builtin tools: injected into every agent spec, never bound explicitly */
    fun selectRequiredTools(): List<AgentTool>

    /** Select builtin tool records by Spring bean name (returns multiple records, one per @Tool method) */
    fun selectByBeanName(@Param("beanName") beanName: String): List<AgentTool>

    /** Upsert a builtin tool record (insert or update on duplicate key) */
    fun upsertBuiltinTool(agentTool: AgentTool): Int

    /** Every builtin row including soft-deleted ones — the sync diffs this against the code to prune residue */
    fun selectAllBuiltin(): List<AgentTool>

    /** Hard delete restricted to builtin rows; the sync owns builtin deletion, so no soft-delete state */
    fun deleteBuiltinByIds(@Param("ids") ids: List<Long>): Int
}
