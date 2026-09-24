package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.TeamSkillBinding
import com.agnetix.harnax.entity.dto.SkillTeamBindingCount
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

    /**
     * How many teams bind each of [skillIds]. Skills with no binding are absent.
     *
     * Grouped so a page of skills asks it once, and a count rather than a presence check so the list's
     * switch and `SkillServiceImpl.requireUnbound` read the same number.
     */
    fun selectTeamBindingCounts(@Param("skillIds") skillIds: List<Long>): List<SkillTeamBindingCount>

    /**
     * Cascade for a skill being removed outside the management API — today that only means a CLI
     * package whose shipped skill went with it.
     */
    fun deleteBySkillIds(@Param("skillIds") skillIds: List<Long>): Int
}
