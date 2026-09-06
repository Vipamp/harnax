package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.dto.SkillDetailDto
import com.agnetix.harnax.mapper.SkillMapper
import io.agentscope.core.skill.AgentSkill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * SkillAdaptor Implementation.
 *
 * **Primary path**: reads skill config from [AgentSpecContextHolder] (populated by admin API).
 * **Fallback**: queries DB directly via [SkillMapper].
 */
@Component
class SkillAdaptorImpl(
    private val specContextHolder: AgentSpecContextHolder,
    private val skillMapper: SkillMapper,
    private val objectMapper: ObjectMapper,
) : SkillAdaptor {

    private val log = LoggerFactory.getLogger(SkillAdaptorImpl::class.java)

    override fun getSkill(skillId: Long): AgentSkill? {
        if (skillId <= 0) {
            log.warn("Invalid skillId: $skillId")
            return null
        }

        // Primary: read from context (admin pre-resolved)
        val skillDetails = specContextHolder.get()?.skillDetails
        if (!skillDetails.isNullOrEmpty()) {
            val dto = skillDetails.find { it.id == skillId }
            if (dto != null) {
                log.debug("Skill loaded from context: skillId={}, name={}", skillId, dto.name)
                return buildFromDto(dto)
            }
        }

        // Fallback: direct DB query
        log.debug("Skill fallback to DB: skillId={}", skillId)
        return try {
            val skill = skillMapper.selectById(skillId)
            if (skill == null) {
                log.warn("Skill not found: $skillId")
                return null
            }
            if (skill.status == 0) {
                // selectById only filters `active`, so the disable flag has to be honoured here: admin
                // applies it on every delivery path (`/builtin-skills` and both branches of the agent
                // spec), and this fallback must not be the one route that loads a switched-off skill
                log.info("Skill '{}' (id={}) is disabled, skipping", skill.name, skill.id)
                return null
            }
            buildFromEntity(skill)
        } catch (e: Exception) {
            log.error("Failed to load skill: $skillId", e)
            null
        }
    }

    private fun buildFromDto(dto: SkillDetailDto): AgentSkill? = try {
        val builder = AgentSkill.builder()
            .name(dto.name)
            .skillContent(dto.skillmd)
            .description(dto.description)

        val resources = parseResources(dto.resources)
        if (resources.isNotEmpty()) {
            builder.resources(resources)
        }

        builder.build()
    } catch (e: Exception) {
        log.error("Failed to build AgentSkill from DTO: ${dto.id}", e)
        null
    }

    private fun buildFromEntity(skill: Skill): AgentSkill? = try {
        val builder = AgentSkill.builder()
            .name(skill.name)
            .skillContent(skill.skillmd)
            .description(skill.description)

        val resources = parseResources(skill.resources)
        if (resources.isNotEmpty()) {
            builder.resources(resources)
        }

        builder.build()
    } catch (e: Exception) {
        log.error("Failed to build AgentSkill from entity: ${skill.id}", e)
        null
    }

    private fun parseResources(resourcesJson: String): Map<String, String> {
        if (resourcesJson.isBlank()) return emptyMap()
        return try {
            objectMapper.readValue(resourcesJson, object : TypeReference<Map<String, String>>() {})
        } catch (e: Exception) {
            log.warn("Failed to parse resources JSON", e)
            emptyMap()
        }
    }
}
