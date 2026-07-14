package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.agent.skill.store.SkillContentReader
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
    private val skillContentReader: SkillContentReader,
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
            buildFromEntity(skill)
        } catch (e: Exception) {
            log.error("Failed to load skill: $skillId", e)
            null
        }
    }

    private fun buildFromDto(dto: SkillDetailDto): AgentSkill? = try {
        val (skillmd, resources) = loadSkillContent(dto.storagePath, dto.skillmd, dto.resources)

        val builder = AgentSkill.builder()
            .name(dto.name)
            .skillContent(skillmd)
            .description(dto.description)

        if (resources.isNotEmpty()) {
            builder.resources(resources)
        }

        builder.build()
    } catch (e: Exception) {
        log.error("Failed to build AgentSkill from DTO: ${dto.id}", e)
        null
    }

    private fun buildFromEntity(skill: Skill): AgentSkill? = try {
        val (skillmd, resources) = loadSkillContent(skill.storagePath, skill.skillmd, skill.resources)

        val builder = AgentSkill.builder()
            .name(skill.name)
            .skillContent(skillmd)
            .description(skill.description)

        if (resources.isNotEmpty()) {
            builder.resources(resources)
        }

        builder.build()
    } catch (e: Exception) {
        log.error("Failed to build AgentSkill from entity: ${skill.id}", e)
        null
    }

    private fun loadSkillContent(storagePath: String, fallbackSkillmd: String, resourcesJson: String): Pair<String, Map<String, String>> {
        // Try loading from external storage first (MinIO/local)
        if (storagePath.isNotBlank()) {
            try {
                val content = skillContentReader.load(storagePath)
                return content.skillmd to content.resources
            } catch (e: Exception) {
                log.warn("Failed to load skill content from store (path=$storagePath), falling back to inline fields", e)
            }
        }

        // Parse resources JSON
        val resources = if (resourcesJson.isNotBlank()) {
            try {
                objectMapper.readValue(
                    resourcesJson,
                    object : TypeReference<Map<String, String>>() {},
                )
            } catch (e: Exception) {
                log.warn("Failed to parse resources JSON", e)
                emptyMap()
            }
        } else {
            emptyMap()
        }

        return fallbackSkillmd to resources
    }
}
