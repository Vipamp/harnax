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
                // Only a GIT source can be rebuilt from the legacy columns. Pretending otherwise
                // makes an NPM/ZIP repository fail later with "requires 'packageName'" and hides
                // the real cause, which is a corrupt sourceConfig.
                if (repository.sourceType.equals("GIT", ignoreCase = true)) {
                    log.warn("Failed to parse sourceConfig of repository {}, falling back to url/branch", repository.id, e)
                } else {
                    throw IllegalStateException(
                        "sourceConfig of repository ${repository.id} (${repository.sourceType}) is not valid JSON: ${e.message}",
                        e,
                    )
                }
            }
        }
        return mapOf("url" to repository.url, "branch" to repository.branch)
    }

    /**
     * Configuration as API consumers should see it.
     *
     * A pure projection of the stored JSON, unlike [parse]: transport-only keys such as `zipPath`
     * are an internal server path and stay on the server, and nothing is synthesized from the legacy
     * url/branch columns. Both response DTOs expose those columns top-level, so inventing a
     * Git-looking map here would only make GIT and NPM behave differently for the same blank input.
     * Shared by both DTOs so the two skill APIs cannot drift apart again.
     */
    fun forApi(repository: SkillRepository): Map<String, Any>? {
        if (repository.sourceConfig.isBlank()) {
            return null
        }
        return try {
            objectMapper.readValue(
                repository.sourceConfig,
                object : TypeReference<Map<String, Any>>() {},
            ).filterKeys { it !in INTERNAL_KEYS }
        } catch (e: Exception) {
            log.warn("Failed to parse sourceConfig of repository {} for the API", repository.id, e)
            null
        }
    }

    /** Keys that never leave the server. */
    private val INTERNAL_KEYS = setOf("zipPath")

    /**
     * Trims every text value of a config about to be stored.
     *
     * URLs and branch names reach this service pasted from a chat window or a terminal, so they
     * arrive padded. The loaders trim before they act, which keeps a padded source working, but
     * the padded text was what got written: the list page then showed an address nobody typed, the
     * edit form offered it back for editing, and the legacy `url` / `branch` columns disagreed
     * with whatever a loader had normalised. Doing it once, on the way in, keeps the stored config
     * canonical for every reader. Rows written before this are still handled by the loaders.
     */
    fun normalized(config: Map<String, Any>): Map<String, Any> = config.mapValues { (_, value) ->
        if (value is String) value.trim() else value
    }
}
