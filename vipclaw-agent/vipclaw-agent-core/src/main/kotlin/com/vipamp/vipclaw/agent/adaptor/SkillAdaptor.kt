package com.vipamp.vipclaw.agent.adaptor

import io.agentscope.core.skill.AgentSkill

/**
 * @Author: heqingsong
 * @Date: 2026/3/31
 * @Description: SkillAdaptor
 * @Project: vipclaw
 */
fun interface SkillAdaptor {

    fun getSkill(skillId: Long): AgentSkill?
}
