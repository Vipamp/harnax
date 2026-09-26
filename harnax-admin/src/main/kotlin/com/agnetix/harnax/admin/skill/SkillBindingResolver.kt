package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.TenantResolver
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.mapper.SkillMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The rule a skill must satisfy to be bound to something, applied to both holders a skill can have:
 * an ordinary agent and a team's lead.
 *
 * It lives outside either service because the two entry points must refuse the same bad input with the
 * same sentence. A binding the runtime would later drop with nothing but a log line is how an operator
 * loses a skill without noticing, so every rejection here happens at save time, while the config panel
 * is still open and the row is still fixable.
 */
@Component
class SkillBindingResolver(
    private val jwtUtil: JwtUtil,
    private val skillMapper: SkillMapper,
    private val skillRepositoryService: SkillRepositoryService,
) {

    private val log = LoggerFactory.getLogger(SkillBindingResolver::class.java)

    /**
     * Skill rows for [skillIds], or a refusal naming what is wrong.
     *
     * 1. every id must resolve to a live skill of this tenant (the builtin repository is platform-wide
     *    and exempt, mirroring `SkillServiceImpl.requireReadable`);
     * 2. a disabled skill is out of circulation and binding it would bypass the disable guard;
     * 3. skills of the builtin CLI repository arrive through a CLI binding only;
     * 4. two skills sharing a name cannot both be loaded, because the harness keys skills by name.
     *
     * Duplicate ids collapse: the same skill twice in one request would otherwise write two binding rows
     * and hit the (holder, skill_id) unique key with a database error the user cannot read.
     */
    fun resolveBindable(skillIds: List<Long>): List<Skill> {
        val ids = skillIds.distinct()
        if (ids.isEmpty()) return emptyList()

        // Resolved without a tenant filter: the builtin repository is a single platform-wide row, so a
        // tenant-scoped lookup missed it and silently dropped the constraint below
        val builtinRepo = skillRepositoryService.getBuiltinRepository()
        val boundSkills = resolveExisting(ids, builtinRepo?.id)
        if (builtinRepo == null) {
            // No builtin repository means no builtin skills exist, so there is nothing to reject
            log.warn("Builtin repository '{}' not found, skipping skill binding constraint", BuiltinRepository.CLI_SKILLS)
        } else {
            val invalid = boundSkills.filter { it.repositoryId == builtinRepo.id }
            if (invalid.isNotEmpty()) {
                throw BizException(
                    "Skills from '${BuiltinRepository.CLI_SKILLS}' cannot be bound directly (auto-loaded via CLI): ${invalid.joinToString(",") { it.name }}",
                )
            }
        }

        val duplicatedNames = boundSkills.groupBy { it.name }.filter { it.value.size > 1 }.keys
        if (duplicatedNames.isNotEmpty()) {
            throw BizException(
                "Skills bound together must have distinct names, duplicated: ${duplicatedNames.joinToString(",")}",
            )
        }
        return boundSkills
    }

    /**
     * The tenant this request acts within. [TenantResolver] holds the chain and the reason a request
     * without `X-Tenant-ID` is read as the caller's own tenant rather than as tenant 1 — the same answer
     * [com.agnetix.harnax.admin.service.impl.SkillServiceImpl] gives when it stamps a new skill row, so
     * the skill a caller just created is the skill this guard finds.
     *
     * It only widens the lookup to the tenant the caller already owns; [deliverableWithin] keeps its
     * exact-match predicate and the builtin repository stays the single exemption it was.
     */
    private fun currentTenantId(): Long = TenantResolver.resolve(jwtUtil)

    private fun resolveExisting(
        skillIds: List<Long>,
        builtinRepositoryId: Long?,
    ): List<Skill> {
        val tenantId = currentTenantId()
        val resolvable = deliverableWithin(skillIds, tenantId, builtinRepositoryId)
        val missing = skillIds - resolvable.map { it.id }.toSet()
        if (missing.isNotEmpty()) {
            throw BizException(
                "Skill is missing, deleted, or outside your tenant: ${missing.joinToString(",")}",
            )
        }
        val disabled = resolvable.filter { it.status != 1 }
        if (disabled.isNotEmpty()) {
            throw BizException("Skill is disabled, enable it before binding: ${disabled.joinToString(",") { it.name }}")
        }
        return resolvable
    }

    /**
     * Rows among [skillIds] that [tenantId] may receive — the same set a binding may point at.
     *
     * `SkillMapper.selectByIds` has no tenant condition, and an internal delivery call carries no
     * trustworthy tenant header, so the holder's own tenant is the only comparable basis: without it a
     * cross-tenant binding row, saved before the save-time check existed, hands over another tenant's
     * SKILL.md and every bundled resource. The builtin repository is a platform-wide row and stays
     * exempt, exactly as [com.agnetix.harnax.admin.service.impl.SkillServiceImpl.requireReadable] has
     * to treat it — otherwise the CLI-shipped skills reach nobody but the seeding tenant.
     */
    fun deliverable(
        skillIds: List<Long>,
        tenantId: Long,
    ): List<Skill> {
        // Checked before the builtin lookup: an agent with no skills must not pay for a SELECT
        if (skillIds.isEmpty()) return emptyList()
        return deliverableWithin(skillIds, tenantId, skillRepositoryService.getBuiltinRepository()?.id)
    }

    /**
     * The filter both readers share. Callers hold the non-empty precondition: [selectByIds] with an empty
     * list renders `IN ()`, which MySQL rejects.
     */
    private fun deliverableWithin(
        skillIds: List<Long>,
        tenantId: Long,
        builtinRepositoryId: Long?,
    ): List<Skill> = skillMapper.selectByIds(skillIds).filter {
        it.tenantId == tenantId || it.repositoryId == builtinRepositoryId
    }
}
