package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Team
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface TeamMapper {

    fun selectById(@Param("id") id: Long): Team?

    fun insert(team: Team): Int

    fun updateById(team: Team): Int

    fun deleteById(@Param("id") id: Long): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    fun selectTeamList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long? = null,
    ): List<Team>

    fun selectByName(@Param("name") name: String, @Param("tenantId") tenantId: Long): Team?

    /** Teams an agent leads — used to refuse deleting an agent a live team still references. */
    fun selectByLeadAgentId(@Param("agentId") agentId: Long): List<Team>
}
