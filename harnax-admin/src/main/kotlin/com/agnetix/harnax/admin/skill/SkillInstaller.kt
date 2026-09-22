package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.dto.SkillInstallResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.skill.loader.SkillLoadFailure
import com.agnetix.harnax.admin.util.ApiErrors
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import com.agnetix.harnax.mapper.TeamSkillBindingMapper
import io.agentscope.core.skill.AgentSkill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Persists skills produced by a source loader.
 *
 * Both entry points (the new `skill-sources` install and the legacy selective `skills/batch` sync)
 * go through here, so they share one upsert rule set and one way of reporting failures. Keeping it
 * in its own bean also keeps the transaction short: loading from GIT/NPM happens *before* this is
 * called, so no clone or `npm install` ever runs while row locks are held.
 */
@Service
class SkillInstaller(
    private val skillMapper: SkillMapper,
    private val skillRepositoryMapper: SkillRepositoryMapper,
    private val agentSkillBindingMapper: AgentSkillBindingMapper,
    private val teamSkillBindingMapper: TeamSkillBindingMapper,
) {

    private val log = LoggerFactory.getLogger(SkillInstaller::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * Inserts the repository and its skills atomically.
     *
     * @param loadFailures directories the loader could not parse, reported alongside the skills
     *                       that failed to persist
     * @param duplicateCheck re-runs the name lookup inside the transaction; the unique index is the
     *                       real guard, this only produces a friendlier error for the common race
     */
    @Transactional(rollbackFor = [Exception::class])
    fun createWithSkills(
        repository: SkillRepository,
        agentSkills: List<AgentSkill>,
        loadFailures: List<SkillLoadFailure> = emptyList(),
        duplicateCheck: () -> Boolean,
    ): SkillInstallResponse {
        if (duplicateCheck()) {
            throw BizException("Source name already exists")
        }
        skillRepositoryMapper.insert(repository)
        return persist(repository, agentSkills, only = null, loadFailures = loadFailures)
    }

    /**
     * Upserts loaded skills into [repository].
     *
     * @param only when set, only these names are stored (selective sync); names missing from the
     *             source are reported as failures instead of being skipped silently. Iteration
     *             order is preserved so the report reads like the selection dialog.
     * @param loadFailures directories the loader could not turn into a skill. They join the same
     *             `failed` list as the persistence errors, so the report covers the whole pipeline
     *             instead of only the half that happens inside this transaction.
     */
    @Transactional(rollbackFor = [Exception::class])
    fun persist(
        repository: SkillRepository,
        agentSkills: List<AgentSkill>,
        only: Collection<String>? = null,
        loadFailures: List<SkillLoadFailure> = emptyList(),
    ): SkillInstallResponse {
        val installed = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val failed = mutableListOf<SkillInstallResponse.FailedSkill>()
        val flagged = mutableListOf<SkillInstallResponse.FlaggedSkill>()
        val seen = mutableSetOf<String>()

        // Reported before the selection is resolved so the reason stays accurate: a directory whose
        // SKILL.md could not be parsed never becomes an `AgentSkill`, so without this the operator
        // would only learn that the name is "not present in the source anymore". Under a selective
        // sync only the requested names are echoed — these failures are keyed on the directory name,
        // which need not match the name the preview showed, so listing all of them would blame
        // skills nobody asked for.
        //
        // The two source-level pseudo-names are not skills and leave the list for their own fields:
        // counting them made "the archive holds no skill" read as "1 of 1 skills failed to store".
        val unreadable = mutableSetOf<String>()
        // The selection narrows which *skills* this run stores, not whether the run reached the
        // source: filtering the source-level names out answered an unreachable remote with an empty
        // report and no reason
        val reportedLoadFailures = loadFailures.filter {
            val name = it.name.trim()
            only == null || name in only || SkillLoadFailure.isSourceLevel(name)
        }
        var sourceError: String? = null
        var emptyReason: String? = null
        reportedLoadFailures.forEach { loadFailure ->
            val name = loadFailure.name.trim()
            when (name) {
                SkillLoadFailure.WHOLE_SOURCE -> sourceError = loadFailure.reason
                SkillLoadFailure.EMPTY_SOURCE -> emptyReason = loadFailure.reason
                else -> {
                    unreadable.add(name)
                    failed.add(
                        SkillInstallResponse.FailedSkill(
                            if (name.length > MAX_SKILL_NAME_LENGTH) name.take(MAX_SKILL_NAME_LENGTH) + "..." else name,
                            loadFailure.reason,
                        ),
                    )
                }
            }
        }

        val candidates = if (only == null) {
            agentSkills
        } else {
            // Keyed on the trimmed name, which is what gets stored below and what the caller sends
            // back after normalising the selection: keying on the raw value made a source that
            // declares " my-skill " answer "Not present in the source anymore" to a request for
            // "my-skill". The first occurrence wins so a selective sync resolves a repeated name the
            // same way the full import does, instead of silently storing the last one
            val byName = LinkedHashMap<String, AgentSkill>()
            for (agentSkill in agentSkills) {
                val key = agentSkill.name?.trim().orEmpty()
                if (key.isNotEmpty()) {
                    byName.putIfAbsent(key, agentSkill)
                }
            }
            only.distinct().mapNotNull { name ->
                byName[name] ?: run {
                    // Already reported above with the real reason; adding it here as well put the
                    // same directory in the list twice. A run that never read the source has no
                    // evidence about any name, so only `sourceError` speaks for it
                    if (name !in unreadable && sourceError == null) {
                        failed.add(SkillInstallResponse.FailedSkill(name, "Not present in the source anymore"))
                    }
                    null
                }
            }
        }

        if (candidates.isEmpty() && failed.isEmpty()) {
            log.warn("Source {} ({}) yielded no skills", repository.id, repository.sourceType)
        }

        for (agentSkill in candidates) {
            val name = agentSkill.name?.trim()?.takeIf { it.isNotBlank() }
            if (name == null) {
                failed.add(SkillInstallResponse.FailedSkill("<unnamed>", "Skill declares no name"))
                continue
            }

            // `skill.name` is a varchar(100). Letting a longer one through reaches MySQL and answers
            // with a data-truncation error that names a column instead of the offending skill
            if (name.length > MAX_SKILL_NAME_LENGTH) {
                failed.add(
                    SkillInstallResponse.FailedSkill(
                        name.take(MAX_SKILL_NAME_LENGTH) + "...",
                        "Skill name is longer than the $MAX_SKILL_NAME_LENGTH characters allowed",
                    ),
                )
                continue
            }

            val content = agentSkill.skillContent ?: ""
            if (content.isBlank()) {
                failed.add(SkillInstallResponse.FailedSkill(name, "SKILL.md is empty"))
                continue
            }

            // A source can declare the same name twice (two SKILL.md files sharing one frontmatter
            // name). Without this the second occurrence finds the row the first one just wrote in
            // this same transaction, is counted as an update on top of the insert, and `savedCount`
            // reports two skills where the repository only holds one
            if (!seen.add(name)) {
                failed.add(SkillInstallResponse.FailedSkill(name, "Duplicate skill name in the source"))
                continue
            }

            try {
                val resources = agentSkill.resources ?: emptyMap()
                val resourcesJson = objectMapper.writeValueAsString(resources)

                val findings = SkillContentScanner.scan(content, resources)
                // Flagged content is stored disabled; re-importing re-arms the review gate
                val targetStatus = if (findings.isEmpty()) 1 else 0

                val existing = skillMapper.selectByNameAndRepo(name, repository.id)
                if (existing != null) {
                    existing.description = agentSkill.description ?: ""
                    existing.skillmd = content
                    existing.resources = resourcesJson
                    existing.version = repository.version
                    existing.isPublic = repository.isPublic
                    skillMapper.updateById(existing)
                    // updateById deliberately excludes status, so it needs its own statement.
                    // This is the one write that disables a bound skill on purpose (D7): flagged
                    // content stays out of circulation even when it would strand a binding
                    if (existing.status != targetStatus) {
                        skillMapper.updateStatus(existing.id, targetStatus)
                    }
                    updated.add(name)
                    log.info("Updated skill: {}", name)
                } else {
                    skillMapper.insert(
                        Skill().apply {
                            this.tenantId = repository.tenantId
                            this.name = name
                            this.repositoryId = repository.id
                            this.description = agentSkill.description ?: ""
                            this.skillmd = content
                            this.resources = resourcesJson
                            this.version = repository.version
                            this.status = targetStatus
                            this.active = 1
                            // Inherit the repository visibility: a public repository whose skills
                            // stay private looks like data loss in the skill list
                            this.isPublic = repository.isPublic
                            this.creator = repository.creator
                        },
                    )
                    installed.add(name)
                    log.info("Created skill: {}", name)
                }

                // Reported only once the row exists: recording it before the write put the same name
                // in both `flagged` and `failed` whenever persistence threw, and the response then
                // claimed a skill had been stored disabled that was never stored at all
                if (findings.isNotEmpty()) {
                    flagged.add(
                        SkillInstallResponse.FlaggedSkill(
                            name = name,
                            reasons = findings.map { "${it.resource}: ${it.reason}" }.distinct(),
                        ),
                    )
                }
            } catch (e: Exception) {
                // One broken skill must not silently vanish: it is reported back to the caller. The
                // reason travels inside a successful HTTP response, so it goes through the same
                // filter the controllers apply rather than quoting the failing statement
                log.error("Failed to install skill {} of source {}: {}", name, repository.id, e.message, e)
                failed.add(SkillInstallResponse.FailedSkill(name, ApiErrors.message(e, e.javaClass.simpleName)))
            }
        }

        // Read after the writes: `updated` is a row found by name, `installed` a row just inserted,
        // and inside this transaction both are visible to the query below. A source that never
        // answered is not a source that dropped everything, so `sourceError` shuts the comparison off.
        val stale = if (only == null && sourceError == null) {
            staleNames(repository, agentSkills, failed, (installed + updated).toSet())
        } else {
            emptyList()
        }

        return SkillInstallResponse(
            installed = installed,
            updated = updated,
            failed = failed,
            flagged = flagged,
            sourceError = sourceError,
            emptyReason = emptyReason,
            stale = stale,
        )
    }

    /**
     * Skills this repository holds that the source no longer does.
     *
     * Nothing is deleted here — a skill the source dropped may still be bound to agents, and the
     * bindings would go with it. Reporting it is the whole of the job.
     */
    private fun staleNames(
        repository: SkillRepository,
        agentSkills: List<AgentSkill>,
        failed: List<SkillInstallResponse.FailedSkill>,
        stored: Set<String>,
    ): List<String> {
        // A name the source does hold but this run could not store is already in `failed` with its
        // reason; calling it stale as well would report one problem as two, one of them wrong
        val stillThere = agentSkills.map { it.name?.trim().orEmpty() }.toSet() + failed.map { it.name }
        return skillMapper.selectByRepositoryId(repository.id)
            .map { it.name }
            .filter { it !in stored && it !in stillThere }
            .sorted()
    }

    /**
     * Removes a repository together with its skills and every binding that points at them.
     *
     * Requires every skill under it to be disabled first, and none of them to still be bound. The
     * per-skill guards stop at the skill, and this cascade reaches past them into the same agent
     * bindings, so a source that still holds an enabled skill is a source an agent is still using.
     * Disabled covers that except for the one write D7 allows past the guard — a content-scan hit
     * force-disables a bound skill — so the bindings are counted on their own rather than inferred
     * from the status.
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteWithSkills(repository: SkillRepository) {
        val skills = skillMapper.selectByRepositoryId(repository.id)
        val enabled = skills.count { it.status == 1 }
        if (enabled > 0) {
            val noun = if (enabled == 1) "1 skill is" else "$enabled skills are"
            throw BizException("$noun still enabled, so this source cannot be deleted")
        }
        val skillIds = skills.map { it.id }
        if (skillIds.isNotEmpty()) {
            val bound = agentSkillBindingMapper.selectAgentBindingCounts(skillIds).size
            if (bound > 0) {
                val noun = if (bound == 1) "1 skill is" else "$bound skills are"
                throw BizException("$noun still bound to an agent, so this source cannot be deleted")
            }
            val teamBound = teamSkillBindingMapper.selectBoundSkillIds(skillIds).size
            if (teamBound > 0) {
                val noun = if (teamBound == 1) "1 skill is" else "$teamBound skills are"
                throw BizException("$noun still bound to a team lead, so this source cannot be deleted")
            }
            agentSkillBindingMapper.deleteBySkillIds(skillIds)
        }
        skills.forEach { skillMapper.deleteById(it.id) }
        skillRepositoryMapper.deleteById(repository.id)
    }

    private companion object {
        /** Mirrors the `skill.name` column, see `V1__init_schema.sql`. */
        const val MAX_SKILL_NAME_LENGTH = 100
    }
}
