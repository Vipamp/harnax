package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTool
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentToolMapper {

    fun selectById(@Param("id") id: Long): AgentTool?

    /** Batch load undeleted tools by id (spec delivery resolves all bound + required tools at once) */
    fun selectByIds(@Param("ids") ids: List<Long>): List<AgentTool>

    fun selectByName(@Param("name") name: String): AgentTool?

    fun selectAgentToolList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
    ): List<AgentTool>

    /** Tools an agent may bind: enabled and non-mandatory */
    fun selectAvailableTools(): List<AgentTool>

    fun selectBuiltinToolList(): List<AgentTool>

    /** Required tools: injected into every agent spec, never bound explicitly */
    fun selectRequiredTools(): List<AgentTool>

    /** Select tool records by Spring bean name (returns multiple records, one per @Tool method) */
    fun selectByBeanName(@Param("beanName") beanName: String): List<AgentTool>

    /** Upsert a builtin tool record (insert or update on duplicate key) */
    fun upsertBuiltinTool(agentTool: AgentTool): Int

    /** Every record including soft-deleted ones — the sync diffs this against the code to prune residue */
    fun selectAllBuiltin(): List<AgentTool>

    /**
     * Hard delete for the sync only. Unguarded by design: the sync owns every row of this table, so
     * an id it lists is a row it wrote.
     */
    fun deleteBuiltinByIds(@Param("ids") ids: List<Long>): Int
}
