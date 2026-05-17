package com.agnetix.harnax.agent.adaptor

import io.agentscope.core.skill.AgentSkill

/**
 * @Author: heqingsong
 * @Date: 2026/3/31
 * @Description: SkillAdaptor
 * @Project: harnax
 */
fun interface SkillAdaptor {

    fun getSkill(skillId: Long): AgentSkill?
}
