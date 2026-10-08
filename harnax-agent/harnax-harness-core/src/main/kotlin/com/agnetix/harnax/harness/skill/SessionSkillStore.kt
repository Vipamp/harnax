package com.agnetix.harnax.harness.skill

import com.agnetix.harnax.harness.sandbox.SandboxFileWriter
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.sandbox.PinnedSandboxFilesystem
import io.agentscope.harness.agent.sandbox.ExecResult
import io.agentscope.harness.agent.sandbox.Sandbox
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner
import org.slf4j.LoggerFactory

/**
 * The out-of-call door onto one session's container: what a session has drafted, what it has enabled, and
 * the copy that makes a draft usable.
 *
 * Out of call is the whole point. [Sandbox] exposes no file methods at all — only `exec(RuntimeContext, …)`,
 * whose context `may be null` (`Sandbox.java:67`), and the docker implementation never reads that argument
 * (`DockerSandbox.java:145-201` runs `docker exec -w <root> <container> sh -c …`). So this holds a live
 * container handle rather than the agent's workspace filesystem, which [SkillDraftStaging] can only reach
 * after the agent exists: an operator enabling a skill over HTTP has no agent to bind to, and after a TTL
 * expiry or a restart there may be none left to bind to at all.
 *
 * Everything here is best-effort in the same shape as the draft reads: no container answers as empty, and
 * no exception is handed to a caller that can do nothing about a stopped sandbox.
 */
class SessionSkillStore(
    private val handles: SandboxHandleProvider,
    private val workspaceRoot: String,
    private val maxEnabled: Int = MAX_ENABLED,
    private val execTimeoutSeconds: Int = EXEC_TIMEOUT_SECONDS,
) {

    private val log = LoggerFactory.getLogger(SessionSkillStore::class.java)

    /** The same workspace filesystem the in-call reads use, pinned to this session's live container. */
    fun filesystemFor(sessionId: String): AbstractFilesystem? = handles.handle(sessionId)?.let { PinnedSandboxFilesystem(it) }

    private fun draftsReader(fs: AbstractFilesystem) = WorkspaceDraftFilesReader(fs, SkillDraftStaging.DRAFTS_DIR)

    private fun enabledReader(fs: AbstractFilesystem) = WorkspaceDraftFilesReader(fs, SkillDraftStaging.SESSION_ENABLED_DIR)

    fun listDraftNames(sessionId: String): List<String> {
        val fs = filesystemFor(sessionId) ?: return emptyList()
        return draftsReader(fs).listDraftSkillNames(null)
    }

    fun listEnabledNames(sessionId: String): List<String> {
        val fs = filesystemFor(sessionId) ?: return emptyList()
        return enabledReader(fs).listDraftSkillNames(null)
    }

    /**
     * One draft as the review queue and the enabling operator need it: text, support files, description.
     * Null when it cannot be read, which the caller reports as "no such draft".
     */
    fun readDraft(
        sessionId: String,
        name: String,
    ): SessionDraft? {
        val fs = filesystemFor(sessionId) ?: return null
        val md = draftsReader(fs).readSkillMarkdown(name, null) ?: return null
        val resources = draftsReader(fs).read(name, null)
        return SessionDraft(
            name = name,
            description = skillDescription(md.content) ?: name,
            skillmd = md.content,
            resources = resources,
        )
    }

    fun listEnabled(sessionId: String): List<EnabledSkill> {
        val fs = filesystemFor(sessionId) ?: return emptyList()
        val reader = enabledReader(fs)
        return reader.listDraftSkillNames(null).mapNotNull { name ->
            val md = reader.readSkillMarkdown(name, null) ?: return@mapNotNull null
            EnabledSkill(
                name = name,
                description = skillDescription(md.content) ?: name,
                enabledAt = md.modifiedAt,
            )
        }
    }

    /**
     * Copies one draft into this session's enabled directory.
     *
     * A copy, not a move: what the reviewer later decides on is the draft in `_drafts`, and the session has
     * to keep working even after that directory is archived or overwritten by the next turn's rewrite. The
     * copy is byte-for-byte because it runs inside the container — `cp -R` never moves the bytes through this
     * process, which would mean base64 chunking for every file and a corrupted binary for anything in
     * `assets/`.
     *
     * The scan runs again here rather than trusting that the write-time scan already ran: `SkillManageTool`
     * does roll a DANGEROUS skill back, but this is the moment a human agrees to let the skill into a model's
     * system prompt, and the verdict they are agreeing with has to be the one computed from these bytes.
     */
    fun enable(
        sessionId: String,
        name: String,
    ): EnableOutcome {
        val sandbox = handles.handle(sessionId)
            ?: return EnableOutcome.NoSandbox
        // Names reach a shell. safeRelativePath is the repository's own gate for exactly that: it refuses
        // quotes, `$`, backticks, newlines and whitespace, so the single quotes below cannot be escaped.
        val safe = SandboxFileWriter.safeRelativePath(name) ?: return EnableOutcome.SourceMissing
        val drafts = "$workspaceRoot/${SkillDraftStaging.DRAFTS_DIR}/$safe"
        val target = "$workspaceRoot/${SkillDraftStaging.SESSION_ENABLED_DIR}/$safe"

        if (!exec(sandbox, "test -f '$drafts/$SKILL_FILE' && echo yes").contains("yes")) {
            return EnableOutcome.SourceMissing
        }
        val draft = readDraft(sessionId, safe) ?: return EnableOutcome.SourceMissing
        val scan = SkillSecurityScanner.scan(safe, draft.skillmd, draft.resources)
        if (!SkillSecurityScanner.shouldAllow(SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
            log.warn(
                "Draft {} of session {} is {} and cannot be enabled in the session",
                safe,
                sessionId,
                scan.verdict(),
            )
            return EnableOutcome.Blocked(scan.verdict().name, findingTexts(scan.findings()))
        }
        val enabled = listEnabledNames(sessionId)
        if (enabled.size >= maxEnabled && !enabled.contains(safe)) {
            return EnableOutcome.Full(enabled.size)
        }
        val copy = execRaw(
            sandbox,
            "mkdir -p '${enabledRoot(workspaceRoot)}' && rm -rf '$target' && " +
                "mkdir -p '$target' && cp -R '$drafts/.' '$target/'",
        )
        if (!copy.ok()) {
            log.warn("Could not enable draft {} for session {}: {}", safe, sessionId, copy.stderr())
            return EnableOutcome.Failed(copy.stderr().orEmpty().ifEmpty { "the container refused the copy" })
        }
        log.info(
            "Draft {} enabled for session {} (verdict {}, {} findings)",
            safe,
            sessionId,
            scan.verdict(),
            scan.findings().size,
        )
        return EnableOutcome.Enabled(safe, scan.verdict().name, scan.findings().size)
    }

    private fun exec(
        sandbox: Sandbox,
        command: String,
    ): String = execRaw(sandbox, command).stdout().orEmpty()

    private fun execRaw(
        sandbox: Sandbox,
        command: String,
    ): ExecResult = try {
        sandbox.exec(null, command, execTimeoutSeconds)
    } catch (e: Exception) {
        log.warn("Sandbox command failed for {}: {}", sandboxLabel(sandbox), e.message)
        ExecResult(1, "", e.message ?: e.javaClass.simpleName, false)
    }

    private fun sandboxLabel(sandbox: Sandbox): String = sandbox.javaClass.simpleName

    companion object {
        const val SKILL_FILE = "SKILL.md"

        /** Design D9: these all land in the system prompt of one session. */
        const val MAX_ENABLED = 10

        private const val EXEC_TIMEOUT_SECONDS = 15

        /** Parent directory of the enabled tree, for callers that need it in an absolute path. */
        fun enabledRoot(workspaceRoot: String): String = "$workspaceRoot/${SkillDraftStaging.SESSION_ENABLED_DIR}"
    }
}

/** How this store reaches a container: the keep-alive manager's handle, or a test's double. */
fun interface SandboxHandleProvider {
    fun handle(sessionId: String): Sandbox?
}

data class SessionDraft(
    val name: String,
    val description: String?,
    val skillmd: String,
    val resources: Map<String, String>,
)

data class EnabledSkill(val name: String, val description: String?, val enabledAt: String?)

sealed class EnableOutcome {

    data class Enabled(val name: String, val verdict: String, val findings: Int) : EnableOutcome()

    /** No live container: nothing to write into, and no reason to pretend the draft is gone. */
    data object NoSandbox : EnableOutcome()

    data object SourceMissing : EnableOutcome()

    data class Blocked(val verdict: String, val findings: List<String>) : EnableOutcome()

    data class Full(val count: Int) : EnableOutcome()

    /** The draft was fine and the copy was not: disk full, container died mid-command. */
    data class Failed(val reason: String) : EnableOutcome()
}

/**
 * The `description:` from a skill's YAML front matter, or null.
 *
 * Read by hand rather than through upstream's parser because the queue and the session panel have to show
 * something for a draft whose text is not yet a valid skill — the one case the parser refuses outright.
 */
internal fun skillDescription(markdown: String): String? {
    val lines = markdown.lines()
    if (lines.firstOrNull()?.trim() != "---") return null
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) return null
    return lines.drop(1).take(end)
        .firstOrNull { it.startsWith("description:") }
        ?.substringAfter("description:")
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotEmpty() }
}

/** The finding strings Admin's queue column stores, shared with [AdminBackedPromotionGate]. */
internal fun findingTexts(findings: List<SkillSecurityScanner.Finding>): List<String> = findings.map {
    "${it.patternId()} [${it.severity()}/${it.category()}] ${it.file()}:${it.line()} ${it.description()}"
}
