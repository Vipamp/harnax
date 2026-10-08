package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository
import io.agentscope.core.skill.util.SkillUtil
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import org.slf4j.LoggerFactory

/**
 * The session's enabled skills, as a source the harness can load.
 *
 * A repository of harnax's own rather than an upstream `WorkspaceSkillRepository` pointed at the directory,
 * for one reason: when `skill_manage` is on, `HarnessAgent:2789-2802` walks the installed list backwards,
 * finds the first *read-only* `WorkspaceSkillRepository` and rewrites it in place to point at the promotion
 * directory. A second one installed here would be a candidate for that rewrite, and the session's enabled
 * tree would silently become the place promoted skills land.
 *
 * Reads happen through the agent's bound workspace filesystem, so they are inside a call — which is exactly
 * where the catalog asks ([HarnessSkillMiddleware:322] rebuilds it per call and hands the context to
 * repositories of this kind, re-merging because harnax never builds that middleware with `frozen(...)`).
 * Enabling, by contrast, happens out of a call and goes through
 * [SessionSkillStore]; the two never need the same handle at the same time.
 *
 * Never writeable. `skill_manage` has its own drafts repository, and a model that could write into the
 * directory it reads from would skip both the proposal and the operator's confirmation.
 */
class SessionEnabledSkillRepository(
    private val dir: String = SkillDraftStaging.SESSION_ENABLED_DIR,
) : RuntimeContextSkillRepository {

    private val log = LoggerFactory.getLogger(SessionEnabledSkillRepository::class.java)

    @Volatile
    private var reader: WorkspaceDraftFilesReader? = null

    /** Late binding, same order as [SkillDraftStaging.bind]: the filesystem only exists after `build()`. */
    fun bind(agent: HarnessAgent) {
        val filesystem: AbstractFilesystem? = try {
            agent.workspaceManager?.filesystem
        } catch (e: Exception) {
            log.warn("Could not reach the workspace filesystem of agent '{}': {}", agent.name, e.message)
            null
        }
        if (filesystem != null) bindFilesystem(filesystem)
    }

    /** Separate from [bind] so a test can install a filesystem without a built agent. */
    fun bindFilesystem(filesystem: AbstractFilesystem) {
        reader = WorkspaceDraftFilesReader(filesystem, dir)
    }

    override fun getAllSkills(context: RuntimeContext?): List<AgentSkill> {
        val loaded = reader ?: return emptyList()
        val ctx = context ?: RuntimeContext.empty()
        return loaded.listDraftSkillNames(ctx).mapNotNull { name ->
            val md = loaded.readSkillMarkdown(name, ctx) ?: return@mapNotNull null
            try {
                SkillUtil.createFrom(md.content, loaded.read(name, ctx), SESSION_SKILL_SOURCE)
            } catch (e: Exception) {
                // A skill the model edited into an unparseable state should not take the session's other
                // enabled skills down with it, and the next turn reads the directory again anyway.
                log.warn("Enabled skill {} of this session cannot be loaded: {}", name, e.message)
                null
            }
        }
    }

    override fun getAllSkills(): List<AgentSkill> = getAllSkills(RuntimeContext.empty())

    override fun getAllSkillNames(): List<String> = reader?.listDraftSkillNames(RuntimeContext.empty()) ?: emptyList()

    override fun getSkill(name: String): AgentSkill = getAllSkills(RuntimeContext.empty()).first { it.name == name }

    override fun skillExists(skillName: String): Boolean = getAllSkillNames().contains(skillName)

    override fun save(skills: List<AgentSkill>, force: Boolean): Boolean = false

    override fun delete(skillName: String): Boolean = false

    override fun getRepositoryInfo(): AgentSkillRepositoryInfo = AgentSkillRepositoryInfo(SESSION_SKILL_SOURCE, dir, false)

    override fun getSource(): String = SESSION_SKILL_SOURCE

    override fun setWriteable(writeable: Boolean) {}

    override fun isWriteable(): Boolean = false
}

/**
 * The marker these skills carry.
 *
 * Distinct from `in-memory` on purpose: [SkillUtil.createFrom]'s third argument becomes `AgentSkill.source`
 * and therefore part of `getSkillId()`, and anything that later selects skills by source must be able to
 * tell Admin's delivered ones from a session's own.
 */
const val SESSION_SKILL_SOURCE = "session-enabled"
