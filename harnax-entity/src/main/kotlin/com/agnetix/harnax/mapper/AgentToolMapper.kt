package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTool
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentToolMapper {

    fun selectById(@Param("id") id: Long): AgentTool?

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
}
