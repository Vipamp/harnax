package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillDraft
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Writes the skill row a reviewer approves, and finds the repository it belongs in.
 *
 * Separate from the queue's intake service because the two face opposite directions: intake takes a runtime
 * proposal and must not touch the published table, while this writes the table every agent loads from. The
 * shape mirrors [SkillInstaller] on purpose — same name rules, same rescan, same "flagged content is stored
 * disabled" (D7) — so a skill that arrived from a Git source and one that arrived from an agent differ only
 * in `origin`, and nothing downstream has to know which is which.
 */
@Component
class SkillDraftPromoter(
    private val skillMapper: SkillMapper,
    private val skillRepositoryMapper: SkillRepositoryMapper,
) {

    private val log = LoggerFactory.getLogger(SkillDraftPromoter::class.java)

    /**
     * The tenant's landing repository for approved drafts, created by the first approval that needs it.
     *
     * One row per tenant rather than a platform-wide seeded one: [BuiltinRepository.CLI_SKILLS] is exempt
     * from the tenant predicate on every skill read path, and a promoted skill must not be. Creating it here
     * instead of in the baseline follows the same reasoning — a seeded row belongs to one tenant, so the
     * exemption would be exactly the leak the reserved name is meant to avoid.
     *
     * A concurrent first approval for the same tenant hits `uk_skill_repository_tenant_active_name`; the row
     * it lost the race for is the one it wanted, so the loser reads it back instead of failing.
     */
    fun landingRepository(tenantId: Long): SkillRepository {
        skillRepositoryMapper.selectByName(BuiltinRepository.AGENT_SKILLS, tenantId)
            ?.let { return requireEnabled(it) }
        try {
            skillRepositoryMapper.insert(landingRow(tenantId))
        } catch (e: DuplicateKeyException) {
            // The other insert won, and its row is the one this call wanted; reading it back beats failing
            log.info("Landing repository for tenant {} was created concurrently", tenantId)
        }
        return requireEnabled(
            skillRepositoryMapper.selectByName(BuiltinRepository.AGENT_SKILLS, tenantId)
                ?: throw BizException(
                    "Repository '${BuiltinRepository.AGENT_SKILLS}' could not be created or read back for tenant $tenantId",
                ),
        )
    }

    /**
     * Stores [draft] as a skill named [name] in [repository], replacing whatever already holds that name.
     *
     * The caller has already decided the name is usable — either nothing was there, or a reviewer chose
     * `replace` — and has claimed the draft. So this does not ask: it rescans, writes, and reports what the
     * scan decided.
     *
     * @throws DuplicateKeyException if the row appeared between the caller's check and this write. It has to
     *   reach the caller: the unique index is the only thing standing between two approvals of the same name
     *   and two rows for one skill, and the transaction that claimed the draft must not commit on top of it.
     */
    fun promote(
        draft: SkillDraft,
        repository: SkillRepository,
        name: String,
        reviewer: String,
    ): Promotion {
        val resources = SkillDraftCodec.resourcesOf(draft)
        val findings = SkillContentScanner.scan(draft.skillmd, resources)
        // Flagged content lands disabled, exactly as an import from a Git source does: approval of the
        // proposal is not a review of every command the body quotes, and the second action is deliberate.
        val status = if (findings.isEmpty()) 1 else 0
        val resourcesJson = SkillDraftCodec.resourcesJson(resources)

        val existing = skillMapper.selectByNameAndRepo(name, repository.id)
        if (existing != null) {
            existing.name = name
            existing.description = draft.description ?: ""
            existing.skillmd = draft.skillmd
            // Re-encoded rather than carried over: the column is the canonical form the digest was taken
            // over, and a row whose files no longer sort the way the digest says would misdescribe the skill
            existing.resources = resourcesJson
            existing.version = repository.version
            // creator stays with whoever made the row. The reviewer approved content, they did not write it,
            // and the two columns that do describe this write are the ones set below.
            skillMapper.updateById(existing)
            if (existing.status != status) {
                skillMapper.updateStatus(existing.id, status)
            }
            // The columns updateById deliberately leaves alone, so a promoted-over row does not keep
            // claiming to be human-authored while its body is an agent's
            skillMapper.updateProvenance(existing.id, Skill.ORIGIN_AGENT_PROMOTED, draft.sourceSessionId)
            log.info(
                "Promoted draft {} over skill {} ({}) with {} scan hit(s)",
                draft.id,
                existing.id,
                name,
                findings.size,
            )
            return Promotion(existing.id, status, name, findings.map { "${it.resource}: ${it.reason}" })
        }

        val skill = Skill().apply {
            tenantId = draft.tenantId
            this.name = name
            repositoryId = repository.id
            description = draft.description ?: ""
            skillmd = draft.skillmd
            this.resources = resourcesJson
            version = repository.version
            this.status = status
            active = 1
            // Inherited like the import path does it: a private landing repository would otherwise show
            // every promoted skill to its creator only, and the reviewer is one person in a workspace
            isPublic = repository.isPublic
            creator = reviewer
            origin = Skill.ORIGIN_AGENT_PROMOTED
            originRef = draft.sourceSessionId
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        skillMapper.insert(skill)
        log.info("Promoted draft {} into skill {} ({}) with {} scan hit(s)", draft.id, skill.id, name, findings.size)
        return Promotion(skill.id, status, name, findings.map { "${it.resource}: ${it.reason}" })
    }

    /**
     * What one promotion wrote.
     *
     * [status] is the column value, not "approved": a scan hit means the reviewer still has to enable it, and
     * the response has to say so rather than imply the skill is live.
     */
    data class Promotion(
        val skillId: Long,
        val status: Int,
        val name: String,
        val findings: List<String>,
    )

    private fun landingRow(tenantId: Long) = SkillRepository().apply {
        this.tenantId = tenantId
        name = BuiltinRepository.AGENT_SKILLS
        // No source type of GIT / NPM / ZIP: the loader registry has no BUILTIN fetcher, and
        // requireRefreshable refuses a refresh of this row before a loader is even picked.
        sourceType = BuiltinRepository.SOURCE_TYPE
        description = "Skills this workspace's agents wrote and a reviewer approved"
        status = 1
        // The row is tenant-owned, so its visibility flag is what decides whether every user of this tenant
        // sees the promoted skills in the list. 0 would hide them behind the creator column.
        isPublic = 1
        creator = SYSTEM_CREATOR
        active = 1
        // The insert statement writes these properties rather than leaning on the column defaults, so
        // leaving them unset would store NULL and take the row out of every "ORDER BY update_time" list
        createTime = LocalDateTime.now()
        updateTime = LocalDateTime.now()
    }

    /**
     * A row that exists but is switched off would take the promotion and hide the result: every delivery path
     * compares `status == 1`, so the skill would sit in a repository nobody loads from. The name is reserved,
     * so the only ways to get here are a disabled row an operator made or one an operator disabled — the
     * first is a refusal, the second is theirs to fix.
     */
    private fun requireEnabled(repository: SkillRepository): SkillRepository {
        if (repository.status != 1) {
            throw BizException(
                "Repository '${BuiltinRepository.AGENT_SKILLS}' is disabled, so a promoted skill would never reach an agent. " +
                    "Enable it and approve again.",
            )
        }
        return repository
    }

    companion object {
        private const val SYSTEM_CREATOR = "SYSTEM"
    }
}
