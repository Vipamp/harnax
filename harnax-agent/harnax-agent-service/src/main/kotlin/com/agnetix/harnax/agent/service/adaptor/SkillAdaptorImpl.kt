package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.skill.store.SkillContentReader
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.mapper.SkillMapper
import io.agentscope.core.skill.AgentSkill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Component
class SkillAdaptorImpl(
    private val skillMapper: SkillMapper,
    private val objectMapper: ObjectMapper,
    private val skillContentReader: SkillContentReader,
) : SkillAdaptor {

    private val log = LoggerFactory.getLogger(SkillAdaptorImpl::class.java)

    override fun getSkill(skillId: Long): AgentSkill? {
        if (skillId <= 0) {
            log.warn("Invalid skillId: $skillId")
            return null
        }

        return try {
            val skill = skillMapper.selectById(skillId)
            if (skill == null) {
                log.warn("Skill not found: $skillId")
                return null
            }
            buildAgentSkill(skill)
        } catch (e: Exception) {
            log.error("Failed to load skill: $skillId", e)
            null
        }
    }

    private fun buildAgentSkill(skill: Skill): AgentSkill? = try {
        val (skillmd, resources) = loadSkillContent(skill)

        val builder = AgentSkill.builder()
            .name(skill.name)
            .skillContent(skillmd)
            .description(skill.description)

        if (resources.isNotEmpty()) {
            builder.resources(resources)
        }

        builder.build()
    } catch (e: Exception) {
        log.error("Failed to build AgentSkill for skill: ${skill.id}", e)
        null
    }

    private fun loadSkillContent(skill: Skill): Pair<String, Map<String, String>> {
        if (skill.storagePath.isNotBlank()) {
            try {
                val content = skillContentReader.load(skill.storagePath)
                return content.skillmd to content.resources
            } catch (e: Exception) {
                log.warn("Failed to load skill content from store for skill ${skill.id}, falling back to DB fields", e)
            }
        }

        val resources = if (skill.resources.isNotEmpty()) {
            try {
                objectMapper.readValue(
                    skill.resources,
                    object : TypeReference<Map<String, String>>() {},
                )
            } catch (e: Exception) {
                log.warn("Failed to parse resources JSON for skill: ${skill.id}", e)
                emptyMap()
            }
        } else {
            emptyMap()
        }

        return skill.skillmd to resources
    }
}
