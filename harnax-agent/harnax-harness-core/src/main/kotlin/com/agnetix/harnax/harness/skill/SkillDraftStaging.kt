package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.skill.curator.SkillPromoter
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

/**
 * The one handle the draft pipeline has on a built agent, and the directories the pipeline writes to.
 *
 * Two things are only knowable after `HarnessAgent.Builder.build()` — the workspace filesystem and the agent
 * itself — and both are needed by objects that must already exist to be handed to that builder: the promotion
 * gate reads a draft's support files off the filesystem, and offering a draft calls back into the agent. So
 * this is the placeholder they hold: [bind] fills it once the agent exists, and every read before that
 * answers as if nothing were staged.
 *
 * That ordering is not a nicety. [com.agnetix.harnax.harness.HarnessAgentBuilder.build] configures
 * `enableSkillManageTool` and then builds, so the gate is constructed against an unbound staging and the
 * first draft can only be offered by a middleware running on a later turn, by which time [bind] has run.
 *
 * Binding is one-way and the fields are `@Volatile`: the agent is built on the assembling thread and read
 * from a bounded-Elastic worker, and a session's staging is never re-pointed at another agent.
 */
class SkillDraftStaging : SkillDraftFilesReader {

    val draftsDir: String = DRAFTS_DIR

    val promotedDir: String = PROMOTED_DIR

    @Volatile
    private var agent: HarnessAgent? = null

    @Volatile
    private var reader: WorkspaceDraftFilesReader? = null

    /**
     * Attaches this staging to a built agent, resolving the filesystem the drafts live on from it.
     *
     * An agent whose workspace carries no filesystem leaves the reads empty rather than failing the build:
     * with nothing to list, no draft is offered and the tools simply have no queue behind them.
     */
    fun bind(agent: HarnessAgent) {
        val filesystem: AbstractFilesystem? = try {
            agent.workspaceManager?.filesystem
        } catch (e: Exception) {
            log.warn("Could not reach the workspace filesystem of agent '{}': {}", agent.name, e.message)
            null
        }
        if (filesystem != null) reader = WorkspaceDraftFilesReader(filesystem, draftsDir)
        this.agent = agent
    }

    override fun read(
        skillName: String,
        ctx: RuntimeContext?,
    ): Map<String, String> = reader?.read(skillName, ctx) ?: emptyMap()

    /**
     * The drafts staged for this session, by name; empty until [bind] has run.
     */
    fun listDraftNames(ctx: RuntimeContext?): List<String> = reader?.listDraftSkillNames(ctx) ?: emptyList()

    /**
     * Runs one draft through the promotion pipeline, which is where the gate — and therefore Admin's queue —
     * is reached. Null when no agent is bound yet.
     *
     * The pipeline is upstream's, so the scan that guards what may go live runs here too: this is the only
     * caller in harnax, and a draft the scanner blocks never reaches the queue.
     */
    fun promote(
        name: String,
        reviewerId: String,
        ctx: RuntimeContext?,
    ): Mono<SkillPromoter.PromotionResult>? = agent?.promoteSkill(name, reviewerId, ctx)

    companion object {
        private val log = LoggerFactory.getLogger(SkillDraftStaging::class.java)

        /**
         * Root of the staging area, chosen so that no part of it is a directory the harness or the
         * projection reads: [SandboxSkillProjector.SKILLS_DIR] is where Admin's delivered skills are written
         * for the sandbox, and the harness would otherwise be handed a writable repository pointed at the
         * very tree the model is reading.
         */
        const val STAGING_ROOT = "harnax-skill-staging"

        const val DRAFTS_DIR = "$STAGING_ROOT/_drafts"

        const val PROMOTED_DIR = "$STAGING_ROOT/promoted"

        init {
            // The directories above are compile-time constants, so this can only fire once someone edits
            // them — which is the point: it runs on class load, before any agent is assembled and before
            // anything has been written where a model could read it.
            val clash = listOf(DRAFTS_DIR, PROMOTED_DIR).firstOrNull { it.clashesWithTheSkillsDir() }
            require(clash == null) {
                "Skill staging directory '$clash' overlaps '" + SandboxSkillProjector.SKILLS_DIR +
                    "', the directory Admin's delivered skills are projected into; staged drafts would " +
                    "become a load source the model reads"
            }
        }

        private fun String.clashesWithTheSkillsDir(): Boolean {
            val skills = SandboxSkillProjector.SKILLS_DIR
            return this == skills || startsWith("$skills/") || skills.startsWith("$this/")
        }
    }
}
