package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTool
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * The tool table is written by `BuiltinToolAutoRegistrar` only, and the sync is additive: it inserts
 * a declaration it does not find and updates one it does. There is deliberately no delete here.
 */
@Mapper
interface AgentToolMapper {

    fun selectById(@Param("id") id: Long): AgentTool?

    /** Batch load undeleted tools by id (spec delivery resolves all bound + required tools at once) */
    fun selectByIds(@Param("ids") ids: List<Long>): List<AgentTool>

    /** Identity lookup the sync resolves a declaration with; see the XML for why it ignores `active`. */
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

    /** Insert a declaration the table does not hold yet */
    fun insert(agentTool: AgentTool): Int

    /** Refresh a row the sync resolved by name; `name` itself is never part of this statement */
    fun updateById(agentTool: AgentTool): Int
}
