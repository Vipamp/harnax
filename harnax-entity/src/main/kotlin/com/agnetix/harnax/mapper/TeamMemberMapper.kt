package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.TeamMember
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface TeamMemberMapper {

    fun selectByTeamId(@Param("teamId") teamId: Long): List<TeamMember>

    /** Batch read for the list page, so rendering N teams does not issue N member queries. */
    fun selectByTeamIds(@Param("teamIds") teamIds: List<Long>): List<TeamMember>

    fun selectByMemberAgentId(@Param("agentId") agentId: Long): List<TeamMember>

    fun batchInsert(@Param("list") list: List<TeamMember>): Int

    fun deleteByTeamId(@Param("teamId") teamId: Long): Int
}
