package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.SkillVisibilityResponse
import com.agnetix.harnax.admin.dto.SkillVisibilityUpdateRequest

/**
 * Read and write side of skill visibility: who may load a skill at runtime.
 *
 * Its own service rather than methods on [SkillService] because the two answer different questions with
 * different authority. `SkillService` guards the skill row — content, name, enabled flag — and refuses
 * writes to anything in the platform's builtin repository. A visibility policy is a rollout decision
 * about a row this tenant already owns or may already see, and it has to stay writable for a
 * CLI-shipped skill, which is precisely the case the row guard exists to refuse.
 */
interface SkillVisibilityService {

    /**
     * The policy for one skill, or ALL when no row exists.
     *
     * @throws com.agnetix.harnax.admin.exception.BizException when the skill is gone or outside the
     *   caller's tenant and not a platform-wide builtin skill
     */
    fun get(skillId: Long): SkillVisibilityResponse

    /**
     * Stores [request] as the policy of one skill and returns what was stored.
     *
     * Only the tenant owning the skill may write. Everything the mode does not use is cleared, so the
     * row always describes one rule rather than a mode plus leftovers from the previous one.
     */
    fun update(
        skillId: Long,
        request: SkillVisibilityUpdateRequest,
    ): SkillVisibilityResponse
}
