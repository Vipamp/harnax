package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import org.slf4j.LoggerFactory

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

    companion object {
        /** Upstream's own list, from `SkillPromoter.loadDraftResources`. */
        private val SUPPORT_DIRS = listOf("scripts", "references", "templates", "assets")
    }
}
