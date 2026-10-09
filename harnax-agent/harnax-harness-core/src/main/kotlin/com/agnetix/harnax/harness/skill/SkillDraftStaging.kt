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
 * gate reads a draft's support files off the filesystem, and [promote] runs the upstream pipeline on the
 * agent. So this is the placeholder they hold: [bind] fills it once the agent exists, and every read before
 * that answers as if nothing were staged.
 *
 * Reaching Admin's queue at the end of a turn is not one of the paths through here. The turn-end offer reads
 * the staged drafts through [SessionSkillStore] and files them with the intake adaptor itself, so it never
 * touches this object. [promote] is the other way through, and the live path does not run it either: as
 * [SkillDraftSubmitMiddleware] records, nothing on this deployment calls the upstream pipeline it enters.
 *
 * That ordering is not a nicety. [com.agnetix.harnax.harness.HarnessAgentBuilder.build] configures
 * `enableSkillManageTool` and then builds, so the gate is constructed against an unbound staging. The read
 * that gate performs only happens inside a turn of the agent it was installed on, and by then [bind] has
 * pointed this staging at that agent.
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
     * The pipeline is upstream's, so the scan that guards what may go live runs here too, and a draft the
     * scanner blocks never reaches the queue. Nothing on the live path comes through it any more: the turn-end
     * offer files a draft straight with the intake adaptor, so this is the promotion entry point for callers
     * that promote one deliberately, and [SkillDraftSubmitMiddleware] is no longer among them.
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

        /**
         * Where a skill the operator confirmed for *this session* is copied to, so the model can use it on its
         * next call. Not upstream's [PROMOTED_DIR]: the harness appends the writable repository it builds for
         * promotion at the end of the repository list, which is the winning position on a name clash, so a
         * directory loaded from there would shadow every skill Admin delivered.
         */
        const val SESSION_ENABLED_DIR = "$STAGING_ROOT/session-enabled"

        /** Everything the guard below has to keep out of the delivered-skills tree. */
        val STAGING_DIRS = listOf(DRAFTS_DIR, PROMOTED_DIR, SESSION_ENABLED_DIR)

        init {
            // The directories above are compile-time constants, so this can only fire once someone edits
            // them — which is the point: it runs on class load, before any agent is assembled and before
            // anything has been written where a model could read it.
            val clash = STAGING_DIRS.firstOrNull { dirClashesWithSkillsDir(it) }
            require(clash == null) {
                "Skill staging directory '$clash' overlaps '" + SandboxSkillProjector.SKILLS_DIR +
                    "', the directory Admin's delivered skills are projected into; staged drafts would " +
                    "become a load source the model reads"
            }
        }
    }
}

/** Whether [dir] sits inside [SandboxSkillProjector.SKILLS_DIR], in either direction. */
internal fun dirClashesWithSkillsDir(dir: String): Boolean {
    val skills = SandboxSkillProjector.SKILLS_DIR
    return dir == skills || dir.startsWith("$skills/") || skills.startsWith("$dir/")
}
