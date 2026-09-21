package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
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

    private fun resolveExisting(
        skillIds: List<Long>,
        builtinRepositoryId: Long?,
    ): List<Skill> {
        val tenantId = TenantContext.getTenantId() ?: 1
        val resolvable = skillMapper.selectByIds(skillIds).filter {
            it.tenantId == tenantId || it.repositoryId == builtinRepositoryId
        }
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
}
