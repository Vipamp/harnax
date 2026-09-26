package com.agnetix.harnax.harness.skill

import com.agnetix.harnax.harness.sandbox.SandboxFileWriter
import io.agentscope.core.skill.AgentSkill
import io.agentscope.harness.agent.sandbox.Sandbox
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Puts the skills an agent was given onto its sandbox filesystem, so a model that is told to follow a
 * skill can actually open the files that skill refers to.
 *
 * ## Layout
 *
 * The skills root is `<workspaceRoot>/skills`, one directory per skill, named after
 * [AgentSkill.getName]. The workspace root is the absolute path *inside* the container —
 * `harness.sandbox.workspace-root`, `/workspace` by default — the same root [Sandbox.exec] resolves
 * against, the same one the output-file detector scans as `/workspace/output` and the team
 * orchestrator writes artifacts under. It is also the prefix agentscope's own `ShellPathPolicy` renders
 * into a skill's `<files-root>`, so a skill that says "run `scripts/run.sh`" lands on the path the
 * prompt gives the model:
 *
 * ```
 * /workspace/skills/<skillName>/SKILL.md      <- AgentSkill.skillContent (admin's `skillmd`)
 * /workspace/skills/<skillName>/<relative>    <- each AgentSkill.resources entry
 * /workspace/skills/.harnax-skills.json       <- the manifest, see below
 * ```
 *
 * ## Convergence
 *
 * A keep-alive container is reused across turns, so this cannot be write-only. Every file written here
 * is recorded in `.harnax-skills.json` as its path relative to the skills root mapped to the SHA-256 of
 * the bytes — computed here, because no content hash exists anywhere upstream. On each turn the
 * projection reads that manifest and writes only what is new or changed, removes what the manifest knows
 * about and the current skill set does not (a whole directory for a skill that was unbound, single files
 * for a resource that was dropped or renamed), then rewrites the manifest. A turn whose desired state
 * already matches the manifest issues no write at all, and a container with no manifest — a first turn,
 * or one created before this existed — simply gets everything.
 *
 * Trust is placed in the manifest, not in a stat of each file: an agent that deletes its own skill files
 * mid-session gets them back when its container is rebuilt, not on the next turn.
 *
 * Only paths this projection wrote are ever removed. Anything else sitting under the skills root — a
 * file the agent created there — is left alone.
 *
 * ## Failure posture: deliberately not fatal
 *
 * [project] never throws. A projection error is logged as a WARN naming the skills and the reason, and
 * the turn continues with a skill the model can read the text of but not run. That is the intended
 * behaviour and not an oversight: skill files are an enhancement to a turn that admin already
 * authorised, and a container hiccup — `mkdir` refused, disk full, the exec timeout elapsing — must not
 * turn it into a failed reply. Do not "fix" this into a hard failure without deciding that a session
 * whose skill ships a script should stop answering altogether.
 *
 * ## Where the content comes from
 *
 * Callers pass the [AgentSkill]s the runtime was built with. Those are the ones Admin delivered:
 * `AgentSpecInfoResponse.skillDetails`, tenant-filtered by `SkillBindingResolver.deliverable`, reduced to
 * `AgentSkill` by `SkillAdaptorImpl`. Nothing here reads a skill table — `SkillMapper.selectByIds` has no
 * tenant clause, which is how this repository leaked twice; re-reading it to "fill in" a missing file is
 * the bug, not the fix. A skill absent from the delivery stays absent from the container.
 */
class SandboxSkillProjector(
    workspaceRoot: String,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {

    private val log = LoggerFactory.getLogger(SandboxSkillProjector::class.java)

    /** Absolute path of the skills root inside the container, e.g. `/workspace/skills`. */
    val skillsRoot: String = workspaceRoot.trimEnd('/') + "/" + SKILLS_DIR

    /**
     * Converges the skills root onto [skills]. See the class KDoc for the layout and the failure posture.
     *
     * @return what changed, for the caller's logging and tests; empty when nothing needed doing
     */
    fun project(
        sandbox: Sandbox,
        skills: List<AgentSkill>,
    ): ProjectionResult {
        val rejected = mutableListOf<String>()
        return try {
            val desired = desiredFiles(skills, rejected)
            if (rejected.isNotEmpty()) {
                // Named, because the operator's next question is "why can't the agent run its script".
                log.warn(
                    "Refused {} skill file(s) that do not stay inside their skill directory under {}, so the agent cannot read them: {}",
                    rejected.size,
                    skillsRoot,
                    rejected,
                )
            }
            converge(sandbox, desired).copy(rejected = rejected)
        } catch (e: Exception) {
            // Nothing is committed below this line: the manifest is rewritten only once every file of
            // this turn is in place, so the next turn repeats the work that failed.
            log.warn(
                "Skill projection into {} was skipped for this turn (skills: {}): {}",
                skillsRoot,
                skills.map { it.name }.ifEmpty { listOf("none") },
                e.message ?: e.javaClass.simpleName,
            )
            ProjectionResult(rejected = rejected)
        }
    }

    /** Path relative to the skills root to content. A skill with an unusable name contributes nothing. */
    private fun desiredFiles(
        skills: List<AgentSkill>,
        rejected: MutableList<String>,
    ): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        for (skill in skills) {
            val name = skill.name ?: ""
            // One directory per skill, so the name must be a single path segment.
            val dir = SandboxFileWriter.safeRelativePath(name)?.takeIf { !it.contains('/') }
            if (dir == null) {
                rejected += "skill '$name' (its name is not usable as a directory under the skills root)"
                continue
            }
            val content = skill.skillContent
            if (content.isNullOrBlank()) {
                rejected += "skill '$name' (no SKILL.md content was delivered)"
                continue
            }
            files["$dir/$SKILL_FILE"] = content.toByteArray(StandardCharsets.UTF_8)
            for ((key, value) in skill.resources ?: emptyMap()) {
                // A resources key is admin-authored, but it still names a file this process is about to
                // create inside a container: it must be relative and it must not climb out of `dir`.
                val relative = if (key.trim().startsWith("/")) null else SandboxFileWriter.safeRelativePath(key)
                if (relative == null || value == null) {
                    rejected += "skill '$name' file '$key' (absolute, traversing or blank)"
                    continue
                }
                if (relative == SKILL_FILE) {
                    rejected += "skill '$name' file '$key' (would replace SKILL.md)"
                    continue
                }
                files["$dir/$relative"] = value.toByteArray(StandardCharsets.UTF_8)
            }
        }
        return files
    }

    private fun converge(
        sandbox: Sandbox,
        desired: Map<String, ByteArray>,
    ): ProjectionResult {
        val previous = readManifest(sandbox)
        val stale = previous.keys.filter { !desired.containsKey(it) }
        val pending = desired.filterKeys { previous[it] != digest(desired.getValue(it)) }
        if (desired.isEmpty() && previous.isEmpty()) {
            // Nothing was delivered and nothing was ever written here: leave the container untouched,
            // without even creating the skills root.
            return ProjectionResult.EMPTY
        }
        if (pending.isEmpty() && stale.isEmpty()) {
            return ProjectionResult(unchangedFiles = desired.size)
        }

        val deleted = deleteStale(sandbox, desired.keys, stale)
        val newManifest = LinkedHashMap<String, String>()
        previous.forEach { (path, hash) -> if (desired.containsKey(path)) newManifest[path] = hash }
        pending.forEach { (path, data) ->
            SandboxFileWriter.write(sandbox, "$skillsRoot/$path", data)
            newManifest[path] = digest(data)
            log.debug("Skill projection wrote {}", "$skillsRoot/$path")
        }
        writeManifest(sandbox, newManifest)
        if (deleted.isNotEmpty() || pending.isNotEmpty()) {
            log.info(
                "Skill projection converged {} onto {} file(s), removed {}, left {} unchanged",
                skillsRoot,
                pending.size,
                deleted.size,
                newManifest.size - pending.size,
            )
        }
        return ProjectionResult(
            written = pending.keys.toList(),
            deleted = deleted,
            unchangedFiles = desired.size - pending.size,
        )
    }

    /**
     * Removes the files the manifest lists and this turn no longer wants: a whole skill directory when
     * that skill is gone, otherwise the single file — a renamed or dropped resource.
     *
     * Deletions come before writes so a skill whose files moved this turn converges instead of leaving
     * two directories behind.
     */
    private fun deleteStale(
        sandbox: Sandbox,
        wanted: Set<String>,
        stale: List<String>,
    ): List<String> {
        if (stale.isEmpty()) return emptyList()
        val goneDirs = stale.map { it.substringBefore('/') }
            .distinct()
            .filter { dir -> wanted.none { it.startsWith("$dir/") } }
            .toSet()
        goneDirs.forEach { SandboxFileWriter.delete(sandbox, "$skillsRoot/$it", recursively = true) }
        val orphanFiles = stale.filter { it.substringBefore('/') !in goneDirs }
        orphanFiles.forEach { SandboxFileWriter.delete(sandbox, "$skillsRoot/$it") }
        return goneDirs.map { "$it/" } + orphanFiles
    }

    private fun readManifest(sandbox: Sandbox): Map<String, String> {
        val raw = SandboxFileWriter.read(sandbox, manifestPath()) ?: return emptyMap()
        return try {
            objectMapper.readValue(String(raw, StandardCharsets.UTF_8), MANIFEST_TYPE)
        } catch (e: Exception) {
            // A half-written or hand-edited manifest says nothing about what is on disk, so the caller
            // rewrites every file it owns rather than trusting a hash it cannot read.
            log.warn("Skill manifest {} is unreadable ({}), rewriting every skill file", manifestPath(), e.message)
            emptyMap()
        }
    }

    /** Writes the manifest last, and removes it once there is nothing left to describe. */
    private fun writeManifest(
        sandbox: Sandbox,
        files: Map<String, String>,
    ) {
        if (files.isEmpty()) {
            SandboxFileWriter.delete(sandbox, manifestPath())
            return
        }
        SandboxFileWriter.write(sandbox, manifestPath(), objectMapper.writeValueAsBytes(files))
    }

    private fun manifestPath(): String = "$skillsRoot/$MANIFEST_FILE"

    /**
     * Outcome of one [project] call, for logging and for tests that assert a converged turn did nothing.
     *
     * @param written skill files whose bytes were (re)written this turn, relative to the skills root
     * @param deleted stale files, or whole skill directories, removed this turn
     * @param rejected names refused instead of written — an unusable directory name, a path that climbs
     *   out of the skill directory, a resource that would overwrite `SKILL.md`
     * @param unchangedFiles files already on disk exactly as delivered, so nothing was written for them
     */
    data class ProjectionResult(
        val written: List<String> = emptyList(),
        val deleted: List<String> = emptyList(),
        val rejected: List<String> = emptyList(),
        val unchangedFiles: Int = 0,
    ) {
        val changed: Boolean get() = written.isNotEmpty() || deleted.isNotEmpty()

        companion object {
            val EMPTY = ProjectionResult()
        }
    }

    companion object {
        /** Directory holding every projected skill; see the class KDoc for the full layout. */
        const val SKILLS_DIR = "skills"

        /** The skill text, named the way agentscope's workspace skills name it. */
        const val SKILL_FILE = "SKILL.md"

        private const val MANIFEST_FILE = ".harnax-skills.json"

        private val MANIFEST_TYPE = object : TypeReference<Map<String, String>>() {}

        private fun digest(data: ByteArray): String = "sha256:" +
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}
