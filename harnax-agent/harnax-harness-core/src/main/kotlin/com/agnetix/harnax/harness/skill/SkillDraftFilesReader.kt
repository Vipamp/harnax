package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import org.slf4j.LoggerFactory

/** One draft's skill text and the moment its directory was written, which is what "enabled at" reports. */
data class DraftMarkdown(val content: String, val modifiedAt: String?)

/**
 * Reads the support files of one draft out of the workspace.
 *
 * Exists because upstream hands a review candidate only the first 40 lines of each script, while the bytes
 * that get promoted are the whole file. A queue that stored the heads would show a reviewer a truncated
 * script and then ship the untruncated one.
 */
fun interface SkillDraftFilesReader {
    /**
     * @param skillName the draft's skill name
     * @return every support file, keyed by its path inside the skill directory (e.g. `scripts/run.sh`)
     */
    fun read(
        skillName: String,
        ctx: RuntimeContext?,
    ): Map<String, String>
}

/**
 * Reads those files from a harness workspace directory, the same way the promotion pipeline that scans the
 * draft does: [SUPPORT_DIRS] globbed one level deep, each hit read whole.
 *
 * The directory list is upstream's and deliberately not wider. The scan that decides whether a draft may go
 * live at all covers exactly these four subdirectories, so anything outside them is neither scanned nor
 * promoted — queueing it would show a reviewer a file no gate ever looked at.
 *
 * Reads fail silently in the sense that one unreadable file leaves the others in the map: this is a copy of
 * what a reviewer is about to be asked to decide on, and a queue row that is missing a file says so in its
 * own review, while one that refused to build says nothing at all.
 */
class WorkspaceDraftFilesReader(
    private val filesystem: AbstractFilesystem,
    private val draftsDir: String,
) : SkillDraftFilesReader {

    private val log = LoggerFactory.getLogger(WorkspaceDraftFilesReader::class.java)

    /**
     * The drafts currently staged, by name.
     *
     * A draft is one directory holding a `SKILL.md`, which is how upstream discovers skills as well
     * (`WorkspaceSkillRepository` globs for that same file), so anything the agent wrote elsewhere in the
     * staging area is not a proposal for anything. Exactly one level deep, because a skill the agent deleted
     * is not a proposal either: upstream archives it to `.archive/<name>-<ts>/`, one level lower.
     *
     * A staging area that will not list reads as no drafts, the same posture as [read]: a scan that failed
     * says nothing to anyone, while the next turn's scan gets the same answer anyway.
     */
    fun listDraftSkillNames(ctx: RuntimeContext?): List<String> {
        val effectiveCtx = ctx ?: RuntimeContext.empty()
        val matches = try {
            val glob = filesystem.glob(effectiveCtx, SKILL_FILE, draftsDir)
            if (glob.isSuccess) glob.matches() ?: emptyList() else emptyList()
        } catch (e: Exception) {
            log.warn("Could not list the drafts under {}: {}", draftsDir, e.message)
            emptyList()
        }
        return matches.mapNotNull { match ->
            val path = match.path()?.replace('\\', '/') ?: return@mapNotNull null
            // Relative to the staging directory, a draft's text sits at exactly `<name>/SKILL.md`.
            val segments = path.substringAfter("$draftsDir/", "").split('/')
            segments.takeIf { it.size == 2 && it[1] == SKILL_FILE && it[0].isNotEmpty() }?.first()
        }.distinct()
    }

    override fun read(
        skillName: String,
        ctx: RuntimeContext?,
    ): Map<String, String> {
        val effectiveCtx = ctx ?: RuntimeContext.empty()
        val files = linkedMapOf<String, String>()
        for (dir in SUPPORT_DIRS) {
            val matches = try {
                val glob = filesystem.glob(effectiveCtx, "*", "$draftsDir/$skillName/$dir")
                if (glob.isSuccess) glob.matches() ?: emptyList() else emptyList()
            } catch (e: Exception) {
                log.warn("Could not list {} of draft {}: {}", dir, skillName, e.message)
                emptyList()
            }
            for (match in matches) {
                val path = match.path()?.replace('\\', '/') ?: continue
                // Strip everything up to the support directory so the key matches the path the scanner
                // reported and the path the promoted skill will carry.
                val relPath = path.substringAfter("$dir/", "")
                if (relPath.isEmpty()) continue
                try {
                    val read = filesystem.read(effectiveCtx, path, 0, 0)
                    if (read.isSuccess) {
                        read.fileData()?.content()?.let { files["$dir/$relPath"] = it }
                    }
                } catch (e: Exception) {
                    log.warn("Could not read {} of draft {}: {}", path, skillName, e.message)
                }
            }
        }
        return files
    }

    /**
     * The draft's own `SKILL.md`, which [read] deliberately leaves out: it returns only the support files the
     * review queue stores, while a skill has to be parsed from the text and a session's enabled list has to
     * say when the copy was made.
     *
     * Null when there is nothing to read. A directory without a `SKILL.md` is not a skill by upstream's own
     * discovery rule, so callers treat the absence as "no such draft" instead of inventing an empty skill —
     * which covers the placeholder both filesystems hand back for a file with no text, since forwarding it
     * would hand the parser a system reminder as the skill's body.
     *
     * The timestamp comes from the directory listing, not from the read. Every text read builds its `FileData`
     * with the two-argument constructor, which leaves `createdAt` and `modifiedAt` null
     * (`BaseSandboxFilesystem.java:191`, `LocalFilesystem.java:317`), so on the sandbox and local filesystems
     * in use here the read's own timestamp is dead; only the listing fills it, from the `stat -c '%Y'` its
     * `find` pipeline runs. A read that does carry one — `RemoteFilesystem.java:256` does — keeps its own value
     * and costs no listing.
     *
     * That `stat` output is not what reaches the wire. The seconds are converted on the way into the listing:
     * `BaseSandboxFilesystem.java:405` hands them to `parseEpochSeconds` (`:490-493`), which multiplies by 1000
     * and passes the milliseconds to `FileInfo.ofFile(path, size, long)`, and that renders
     * `Instant.ofEpochMilli(...).toString()` (`FileInfo.java:37-40`). So the string this returns is already
     * ISO-8601 UTC, which is why both clients print it as-is instead of formatting a number.
     */
    fun readSkillMarkdown(
        skillName: String,
        ctx: RuntimeContext?,
    ): DraftMarkdown? {
        val path = "$draftsDir/$skillName/$SKILL_FILE"
        val effectiveCtx = ctx ?: RuntimeContext.empty()
        val read =
            try {
                filesystem.read(effectiveCtx, path, 0, 0)
            } catch (e: Exception) {
                log.warn("Could not read {} of draft {}: {}", path, skillName, e.message)
                return null
            }
        if (!read.isSuccess) return null
        val data = read.fileData() ?: return null
        val content = data.content() ?: return null
        if (content == EMPTY_FILE_MARKER) return null
        return DraftMarkdown(content, data.modifiedAt() ?: listingModifiedAt(skillName, effectiveCtx))
    }

    /**
     * The mtime the listing gives this file.
     *
     * `read` cannot supply one: every text read builds `FileData` with its two-argument constructor, which
     * leaves both timestamps null, so the listing is the only live source on the sandbox and local filesystems.
     * A listing that will not answer reads as no timestamp — the text is still a skill, and a panel that shows
     * no time beats a panel that refuses to show the skill.
     *
     * What comes back is the listing's own rendered instant, not the `stat`'s epoch seconds; see
     * [readSkillMarkdown]'s note for where that conversion happens.
     */
    private fun listingModifiedAt(
        skillName: String,
        ctx: RuntimeContext,
    ): String? {
        val path = "$draftsDir/$skillName/$SKILL_FILE"
        return try {
            val glob = filesystem.glob(ctx, SKILL_FILE, "$draftsDir/$skillName")
            if (!glob.isSuccess) {
                null
            } else {
                glob.matches()?.firstOrNull { it.path()?.replace('\\', '/') == path }?.modifiedAt()
            }
        } catch (e: Exception) {
            log.warn("Could not list {} of draft {}: {}", SKILL_FILE, skillName, e.message)
            null
        }
    }

    companion object {
        /** Upstream's own list, from `SkillPromoter.loadDraftResources`. */
        private val SUPPORT_DIRS = listOf("scripts", "references", "templates", "assets")

        /** Upstream's own marker for "this directory is a skill", the file `WorkspaceSkillRepository` globs for. */
        private const val SKILL_FILE = "SKILL.md"

        /** What both filesystems hand back for a file that exists but has no text (`BaseSandboxFilesystem.java:185`). */
        private const val EMPTY_FILE_MARKER = "System reminder: File exists but has empty contents"
    }
}
