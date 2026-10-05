package com.agnetix.harnax.harness.skill

import com.agnetix.harnax.entity.SkillVisibilityPolicy
import com.agnetix.harnax.entity.dto.SkillVisibilityDto
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.skill.AgentSkill
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Decides which of the delivered skills this conversation may actually see.
 *
 * Written here rather than composed from the upstream `CanaryFilter` / `AllowListFilter` /
 * `EnvironmentFilter` for two reasons. Those three read a sidecar `SkillUsageStore` keyed by skill name and
 * only restrict *agent-authored* skills, so a hand-authored skill passes them unconditionally — which is
 * backwards for this product, where the rollout guard exists precisely for skills an operator configured.
 * And upstream's `CanaryFilter.stableBucket` is package-private, so any percentage rule that has to agree
 * with the harness's own bucketing must reproduce it (see [stableBucket]).
 *
 * The policies arrive with the agent spec, one per skill, already decoded. Nothing here calls out to
 * Admin: this runs on the inference path, once per composed system prompt, and a rollout guard that
 * needs a network round trip would either block the answer or fail open on every timeout
 * (design section 5.4).
 *
 * Keyed by skill **name**, because `AgentSkill` carries no harnax id. That is safe on the delivered set:
 * `SkillBindingResolver` refuses two bindings with the same name, `AgentSpecResolver.withCliSkills`
 * partitions out a CLI skill whose name shadows a bound one, and the harness itself merges repositories by
 * name — so the list this filter receives cannot hold two skills with one name in the first place.
 */
class TenantSkillVisibilityFilter(
    private val policiesByName: Map<String, SkillVisibilityDto>,
    private val userId: Long?,
    private val environment: String = DEFAULT_ENVIRONMENT,
) : SkillVisibilityFilter {

    private val log = LoggerFactory.getLogger(TenantSkillVisibilityFilter::class.java)

    init {
        // Said once, here, rather than per model call: a filter with no identity answers the same way on
        // every call, so a per-call log would flood the prompt composition of every restricted session.
        // Channel conversations (Feishu, WeCom, WeChat) carry no harnax user, so they are the traffic this
        // describes — a restricted skill stays out of them until an operator scopes it by environment.
        val restrictsByUser = policiesByName.values.any {
            it.mode == SkillVisibilityPolicy.MODE_CANARY || it.mode == SkillVisibilityPolicy.MODE_ALLOW_LIST
        }
        if (userId == null && restrictsByUser) {
            log.warn(
                "Skill visibility policies restrict by user but this session has no user identity; " +
                    "CANARY and ALLOW_LIST skills stay hidden for it",
            )
        }
    }

    override fun filter(
        all: List<AgentSkill>?,
        ctx: RuntimeContext?,
    ): List<AgentSkill> {
        val skills = all ?: return emptyList()
        if (skills.isEmpty() || policiesByName.isEmpty()) return skills
        return try {
            // filter() keeps the received instances, which is what the harness matches on: it rebuilds its
            // repository binding through an IdentityHashMap, so a copy of a skill would be dropped silently
            skills.filter { visibleForOneCall(it) }
        } catch (e: Exception) {
            // Same pass-through the harness itself applies when this method throws, stated here so the
            // cost is a lost guard rather than a missing skill. Reaching this would be a bug in a
            // comparison that has no reason to throw.
            log.warn("Skill visibility evaluation failed, delivering every skill unchanged: {}", e.message)
            skills
        }
    }

    private fun visibleForOneCall(skill: AgentSkill): Boolean {
        val policy = skill.name?.let { policiesByName[it] } ?: return true
        return when (policy.mode) {
            SkillVisibilityPolicy.MODE_CANARY -> canaryAllows(policy, skill.name)
            SkillVisibilityPolicy.MODE_ALLOW_LIST -> allowListAllows(policy)
            SkillVisibilityPolicy.MODE_ENV -> environmentAllows(policy)

            // Includes ALL, a blank mode and anything a newer admin knows and this runtime does not:
            // an unreadable rule must not take a working skill away from the people using it
            else -> true
        }
    }

    private fun canaryAllows(
        policy: SkillVisibilityDto,
        skillName: String?,
    ): Boolean {
        val percent = policy.canaryPct
        // Absent means the row carries no percentage at all — unreadable, so visible, the same rule as an
        // unknown mode. Out of range is readable and means what it says: upstream clamps a percentage to
        // 0..100 at construction, so 150 is everybody and -5 is nobody, and reading them otherwise would
        // give one user two answers for one rollout number depending on which filter ran.
        if (percent == null || percent >= MAX_PERCENT) return true
        if (percent <= MIN_PERCENT) return false
        val user = userId ?: return false
        return stableBucket("$user|${skillName.orEmpty()}", BUCKETS) < percent
    }

    private fun allowListAllows(policy: SkillVisibilityDto): Boolean {
        val allowed = policy.userIds
        if (allowed.isEmpty()) return true
        val user = userId ?: return false
        return user in allowed
    }

    private fun environmentAllows(policy: SkillVisibilityDto): Boolean {
        if (policy.environments.isEmpty()) return true
        val current = environment.trim().lowercase(Locale.ROOT)
        return policy.environments.any { it.trim().lowercase(Locale.ROOT) == current }
    }

    companion object {
        /** The label upstream's `environment()` defaults to, and the one harnax runs with unset. */
        const val DEFAULT_ENVIRONMENT = "prod"

        private const val MIN_PERCENT = 0
        private const val MAX_PERCENT = 100
        private const val BUCKETS = 100

        /**
         * The harness's own stable bucket function, reproduced byte for byte: `CanaryFilter.stableBucket`
         * is package-private, and a rollout percentage that bucketed differently from the harness's would
         * give one user two answers depending on which filter happened to run.
         */
        fun stableBucket(key: String, modulus: Int): Int {
            var h = 0
            for (b in key.toByteArray(StandardCharsets.UTF_8)) {
                h = 31 * h + (b.toInt() and 0xFF)
            }
            if (h < 0) {
                // Math.abs(Integer.MIN_VALUE) is itself negative
                h = -(h + 1)
            }
            return h % modulus
        }
    }
}
