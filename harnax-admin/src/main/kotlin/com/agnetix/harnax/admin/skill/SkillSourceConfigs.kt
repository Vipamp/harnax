package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.entity.SkillRepository
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * Parses a skill repository's sourceConfig JSON, falling back to the legacy url/branch columns.
 */
object SkillSourceConfigs {

    private val log = LoggerFactory.getLogger(SkillSourceConfigs::class.java)
    private val objectMapper = ObjectMapper()

    fun parse(repository: SkillRepository): Map<String, Any> {
        if (repository.sourceConfig.isNotBlank()) {
            try {
                return objectMapper.readValue(
                    repository.sourceConfig,
                    object : TypeReference<Map<String, Any>>() {},
                )
            } catch (e: Exception) {
                log.warn("Failed to parse sourceConfig of repository {}, falling back to url/branch", repository.id, e)
            }
        }
        return mapOf("url" to repository.url, "branch" to repository.branch)
    }
}
