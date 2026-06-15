package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.mapper.SkillMapper
import io.agentscope.core.skill.AgentSkill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * SkillAdaptor Implementation
 * Loads skills from database and converts to AgentSkill
 */
@Component
class SkillAdaptorImpl(
    private val skillMapper: SkillMapper,
    private val objectMapper: ObjectMapper,
) : SkillAdaptor {

    private val log = LoggerFactory.getLogger(SkillAdaptorImpl::class.java)

    override fun getSkill(skillId: Long): AgentSkill? {
        if (skillId <= 0) {
            log.warn("Invalid skillId: $skillId")
            return null
        }

        // Query skill information
        val skill = skillMapper.selectById(skillId)
        if (skill == null) {
            log.warn("Skill not found: $skillId")
            return null
        }

        // Convert to AgentSkill
        return buildAgentSkill(skill)
    }

    /**
     * Build AgentSkill
     */
    private fun buildAgentSkill(skill: Skill): AgentSkill? = try {
        val builder = AgentSkill.builder()
            .name(skill.name)
            .skillContent(skill.skillmd)
            .description(skill.description)

        // Deserialize resources JSON string to Map<String, String>
        if (skill.resources.isNotEmpty()) {
            try {
                val resources: Map<String, String> = objectMapper.readValue(
                    skill.resources,
                    object : TypeReference<Map<String, String>>() {},
                )
                builder.resources(resources)
            } catch (e: Exception) {
                log.warn("Failed to parse resources JSON for skill: ${skill.id}", e)
            }
        }

        builder.build()
    } catch (e: Exception) {
        log.error("Failed to build AgentSkill for skill: ${skill.id}", e)
        null
    }
}
