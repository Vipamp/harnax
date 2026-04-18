package com.vipamp.vipclaw.ascopagent.adaptor

import com.vipamp.vipclaw.admin.entity.Skill
import com.vipamp.vipclaw.admin.mapper.SkillMapper
import com.vipamp.vipclaw.agent.adaptor.SkillAdaptor
import io.agentscope.core.skill.AgentSkill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * SkillAdaptor 实现类
 * 从数据库加载技能并转换为 AgentSkill
 */
@Component
class SkillAdaptorImpl(
    private val skillMapper: SkillMapper
) : SkillAdaptor {

    private val log = LoggerFactory.getLogger(SkillAdaptorImpl::class.java)

    override fun getSkill(skillId: Long): AgentSkill? {
        if (skillId <= 0) {
            log.warn("Invalid skillId: $skillId")
            return null
        }

        // 查询技能信息
        val skill = skillMapper.selectById(skillId)
        if (skill == null) {
            log.warn("Skill not found: $skillId")
            return null
        }

        // 转换为 AgentSkill
        return buildAgentSkill(skill)
    }

    /**
     * 构建 AgentSkill
     */
    private fun buildAgentSkill(skill: Skill): AgentSkill? {
        return try {
            AgentSkill.builder()
                .name(skill.name)
                .description(skill.description)
                .build()
        } catch (e: Exception) {
            log.error("Failed to build AgentSkill for skill: ${skill.id}", e)
            null
        }
    }
}
