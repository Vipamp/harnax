package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SkillVisibilityPolicy
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Per-skill runtime visibility policies, one row per skill.
 *
 * Reads here carry no tenant predicate: [selectBySkillIds] is called with ids a caller has already
 * resolved through `SkillBindingResolver.deliverable`, and [selectBySkillId] with an id the service has
 * just permission-checked. A second tenant filter on this table would only be able to disagree with the
 * one that decides what is deliverable at all.
 */
@Mapper
interface SkillVisibilityPolicyMapper {

    fun selectBySkillId(@Param("skillId") skillId: Long): SkillVisibilityPolicy?

    /**
     * The policies of [skillIds], as one batch. Called once per spec delivery, so the runtime never
     * needs a query of its own to decide visibility (design section 5.4).
     */
    fun selectBySkillIds(@Param("skillIds") skillIds: List<Long>): List<SkillVisibilityPolicy>

    /**
     * Insert or replace the policy of [SkillVisibilityPolicy.skillId].
     *
     * One statement rather than a read-then-write, because `uk_skill_visibility_policy_skill` makes the
     * row unique and two operators saving the same skill at once must end with one of their two answers,
     * not with a duplicate-key error neither of them can read.
     */
    fun upsert(policy: SkillVisibilityPolicy): Int

    fun deleteBySkillId(@Param("skillId") skillId: Long): Int
}
