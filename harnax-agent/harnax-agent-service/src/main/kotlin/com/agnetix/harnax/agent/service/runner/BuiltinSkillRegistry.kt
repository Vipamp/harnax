package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.entity.dto.SkillDetailDto
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Registry of built-in skills fetched from admin's built-in repository.
 *
 * Loaded automatically at startup (ApplicationReadyEvent). If admin is
 * unreachable at that time, the registry stays empty and retries lazily
 * on the next [getSkills] call, so agent creation is not permanently
 * deprived of built-in skills.
 */
@Component
class BuiltinSkillRegistry(
    private val adminApiClient: AdminApiClient,
) {

    private val log = LoggerFactory.getLogger(BuiltinSkillRegistry::class.java)

    @Volatile
    private var skills: List<SkillDetailDto> = emptyList()

    @EventListener(ApplicationReadyEvent::class)
    fun loadOnStartup() {
        reload()
    }

    @Synchronized
    fun reload() {
        val fetched = adminApiClient.getBuiltinSkills()
        if (fetched.isNotEmpty()) {
            skills = fetched
            log.info("Built-in skills loaded from admin: {}", fetched.map { "${it.id}:${it.name}" })
        } else {
            log.warn("Built-in skills fetch returned empty — keeping previous cache ({} skills)", skills.size)
        }
    }

    /**
     * Returns cached built-in skills; retries once from admin if the cache is empty.
     */
    fun getSkills(): List<SkillDetailDto> {
        if (skills.isEmpty()) {
            reload()
        }
        return skills
    }
}
