package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.entity.SkillVisibilityPolicy
import com.agnetix.harnax.entity.dto.SkillVisibilityDto
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * The one place that knows how `skill_visibility_policy` encodes its two list columns.
 *
 * Two readers need the same answer — the agent spec delivery and the admin control plane, which shows an
 * operator back what it stored — so the decode lives here rather than in either caller. A second copy
 * that drifted would let the UI display a policy the runtime does not enforce, which is the one failure
 * mode a rollout control cannot afford: the operator would believe the skill is limited to two people
 * while it reaches everyone.
 *
 * Decoding is deliberately lenient. A row this method cannot read comes back with an empty list, and the
 * runtime filter treats an empty restrictive list as "visible" instead of hiding the skill: a corrupt
 * column then costs a rollout guard, not a working skill. The write path validates, so garbage only
 * reaches here from a manual edit of the table.
 */
object SkillVisibilityCodec {

    private val log = LoggerFactory.getLogger(SkillVisibilityCodec::class.java)
    private val objectMapper = ObjectMapper()

    fun toDto(policy: SkillVisibilityPolicy): SkillVisibilityDto = SkillVisibilityDto(
        mode = policy.mode,
        canaryPct = policy.canaryPct,
        userIds = parseUserIds(policy.skillId, policy.userIds),
        environments = parseEnvironments(policy.environments),
    )

    /** Storage format of `user_ids`: a JSON array of ids, or null when the mode carries no list. */
    fun userIdsJson(userIds: List<Long>): String? = if (userIds.isEmpty()) {
        null
    } else {
        objectMapper.writeValueAsString(userIds)
    }

    private fun parseUserIds(skillId: Long, json: String?): List<Long> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, object : TypeReference<List<Long>>() {})
        } catch (e: Exception) {
            log.warn("user_ids of skill {} is not a JSON array of ids, treating the policy as unparseable: {}", skillId, json)
            emptyList()
        }
    }

    /** Storage format of `environments`: a comma separated list of labels, trimmed on the way back. */
    private fun parseEnvironments(csv: String?): List<String> = csv
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
}
