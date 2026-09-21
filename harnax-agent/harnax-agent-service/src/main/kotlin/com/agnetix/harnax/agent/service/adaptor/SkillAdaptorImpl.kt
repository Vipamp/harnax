package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.dto.SkillDetailDto
import io.agentscope.core.skill.AgentSkill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * SkillAdaptor Implementation.
 *
 * Reads skills from [AgentSpecContextHolder], the one place they can be read from: Admin decides what
 * a session is allowed to load — deleted, disabled, or simply outside the caller's reach all come out
 * of the delivered list — and `SkillMapper.selectById` filters only `active`, so reading the database
 * here would hand back content Admin had already withheld, including another tenant's.
 */
@Component
class SkillAdaptorImpl(
    private val specContextHolder: AgentSpecContextHolder,
    private val objectMapper: ObjectMapper,
) : SkillAdaptor {

    private val log = LoggerFactory.getLogger(SkillAdaptorImpl::class.java)

    override fun getSkill(skillId: Long): AgentSkill? {
        if (skillId <= 0) {
            log.warn("Invalid skillId: $skillId")
            return null
        }

        val dto = specContextHolder.get()?.skillDetails?.find { it.id == skillId }
        if (dto == null) {
            // Warn, not debug: a bound skill missing from the delivery is a config disagreement
            // between the two services, and the agent just loses the capability silently otherwise.
            log.warn("Skill $skillId is not in the delivered spec, so it cannot be loaded here")
            return null
        }
        return build(dto)
    }

    private fun build(dto: SkillDetailDto): AgentSkill? = try {
        val builder = AgentSkill.builder()
            .name(dto.name)
            .skillContent(dto.skillmd)
            .description(dto.description)

        val resources = parseResources(dto)
        if (resources.isNotEmpty()) {
            builder.resources(resources)
        }

        builder.build()
    } catch (e: Exception) {
        // Distinct from "not in the delivered spec" above: the row exists and was delivered, but
        // `AgentSkill` refuses a blank name, description or body. Reporting that as a missing skill
        // sends the operator looking for a deletion instead of fixing the row.
        log.error("Skill '{}' (id={}) was delivered but cannot be loaded: {}", dto.name, dto.id, e.message)
        null
    }

    private fun parseResources(dto: SkillDetailDto): Map<String, String> {
        val json = dto.resources
        if (json.isBlank()) return emptyMap()
        return try {
            objectMapper.readValue(json, object : TypeReference<Map<String, String>>() {})
        } catch (e: Exception) {
            // Admin rejects an unparseable value on save now, so this is a row predating that gate;
            // the skill still loads, just with no files, and the name is what makes that traceable.
            log.warn("Skill '{}' (id={}) has unparseable resources JSON, loading with no files", dto.name, dto.id)
            emptyMap()
        }
    }
}
