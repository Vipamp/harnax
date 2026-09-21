package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.TeamSkillBinding
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface TeamSkillBindingMapper {

    fun selectByTeamId(@Param("teamId") teamId: Long): List<TeamSkillBinding>

    fun batchInsert(@Param("list") list: List<TeamSkillBinding>): Int

    fun deleteByTeamId(@Param("teamId") teamId: Long): Int

    /**
     * Which of [skillIds] at least one team binds as lead skills. Skills with no binding are absent.
     *
     * The counterpart of `AgentSkillBindingMapper.selectAgentBindingCounts`: a skill an operator is about
     * to delete has to be checked against both holders, or it disappears from a lead's prompt with no
     * warning.
     */
    fun selectBoundSkillIds(@Param("skillIds") skillIds: List<Long>): List<Long>
}
