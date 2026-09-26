package com.agnetix.harnax.harness.sandbox

import io.agentscope.harness.agent.sandbox.Sandbox
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.UUID

/**
 * File transfer with a running container, which has exactly one channel: [Sandbox.exec].
 *
 * There is no stdin and no `docker cp` behind [Sandbox], so bytes travel as base64 appended chunk by
 * chunk to a temporary file and are decoded into place only once the last chunk has landed: a payload
 * that fails part-way never opens the target at all, and the chunk size keeps a single `printf`
 * argument well inside one `ARG_MAX`.
 *
 * Every path reaching a command here is interpolated into a single-quoted shell argument, so a path
 * from anywhere other than this process's own constants must go through [safeRelativePath] first:
 * anything that can break out of that quote — a `'`, a `$`, a backtick, a newline — has to be refused,
 * not just a `..`.
 *
 * This is the only implementation of these primitives. `TeamOrchestrator` used to carry its own copy
 * of the write loop; both it and the skill projection now call here, so the chunking cannot drift
 * between the two.
 */
object SandboxFileWriter {

    private val log = LoggerFactory.getLogger(SandboxFileWriter::class.java)

    /** Base64 characters per `printf` argument (~48 KiB of original bytes per exec). */
    private const val BASE64_CHUNK = 64 * 1024

    private const val MKDIR_TIMEOUT_SECONDS = 15
    private const val APPEND_TIMEOUT_SECONDS = 30
    private const val DECODE_TIMEOUT_SECONDS = 60
    private const val READ_TIMEOUT_SECONDS = 60
    private const val DELETE_TIMEOUT_SECONDS = 15

    /** The only characters that stay literal inside a single-quoted shell argument. */
    private val SAFE_PATH = Regex("""[\p{L}\p{N}._\- /]+""")

    /**
     * Normalises [path] to a relative path that stays inside the directory it is resolved against, or
     * returns null when it cannot be trusted.
     *
     * Rejected: any `.` or `..` segment — including a harmless-looking `a/../b`, which is refused rather
     * than resolved — a blank segment, and any character that would not survive the single-quoted
     * argument it is going into.
     *
     * A leading `./` or `/` is stripped, so an absolute path comes back as the name it would have inside
     * the workspace, which is what a caller anchoring it at its own root wants. A caller that must refuse
     * an absolute path outright — the skill projection, whose keys are admin-authored — checks for the
     * leading slash itself before calling.
     */
    fun safeRelativePath(path: String): String? {
        val cleaned = path.trim().removePrefix("./").trimStart('/')
        if (cleaned.isEmpty() || !SAFE_PATH.matches(cleaned)) return null
        val segments = cleaned.split('/')
        if (segments.any { it == ".." || it == "." || it.isBlank() }) return null
        return segments.joinToString("/")
    }

    /**
     * Writes [data] to [absolute], creating its parent directory and replacing any previous file. An
     * empty [data] is a valid request and produces an empty file.
     *
     * @throws IllegalStateException when a chunk or the final decode fails
     */
    fun write(
        sandbox: Sandbox,
        absolute: String,
        data: ByteArray,
    ) {
        val encoded = Base64.getEncoder().encodeToString(data)
        val temp = "$absolute.tmp-${UUID.randomUUID()}"
        sandbox.exec(null, "mkdir -p \$(dirname '$absolute')", MKDIR_TIMEOUT_SECONDS)
        try {
            // `"".chunked(n)` is empty: one append of nothing still has to create the temp file, or the
            // decode below would run on a path that does not exist.
            encoded.chunked(BASE64_CHUNK).ifEmpty { listOf("") }.forEach { chunk ->
                val result = sandbox.exec(null, "printf %s '$chunk' >> '$temp'", APPEND_TIMEOUT_SECONDS)
                check(result.exitCode() == 0) { "write to $absolute failed: ${result.stderr().take(200)}" }
            }
            val move = sandbox.exec(null, "base64 -d '$temp' > '$absolute' && rm -f '$temp'", DECODE_TIMEOUT_SECONDS)
            check(move.exitCode() == 0) { "decode of $absolute failed: ${move.stderr().take(200)}" }
        } catch (e: Exception) {
            // Best effort, and deliberately ignored: a leftover temp file is noise, and a failure here
            // must not mask the write failure that got us here.
            runCatching { sandbox.exec(null, "rm -f '$temp'", DELETE_TIMEOUT_SECONDS) }
            throw e
        }
    }

    /**
     * Reads [absolute] whole, or returns null when it is missing, unreadable or empty.
     *
     * A missing file is a normal answer — callers ask "is this already provisioned?" — so the reason is
     * logged at debug and the caller decides how loudly to report the null.
     */
    fun read(
        sandbox: Sandbox,
        absolute: String,
        timeoutSeconds: Int = READ_TIMEOUT_SECONDS,
    ): ByteArray? = try {
        val out = sandbox.exec(null, "base64 '$absolute'", timeoutSeconds).stdout().trim()
        if (out.isEmpty()) null else Base64.getMimeDecoder().decode(out)
    } catch (e: Exception) {
        log.debug("Sandbox read failed for {}: {}", absolute, e.message)
        null
    }

    /** Removes [absolute]; a missing path is not an error, a failed removal is. */
    fun delete(
        sandbox: Sandbox,
        absolute: String,
        recursively: Boolean = false,
    ) {
        val flag = if (recursively) "-rf" else "-f"
        val result = sandbox.exec(null, "rm $flag '$absolute'", DELETE_TIMEOUT_SECONDS)
        check(result.exitCode() == 0) { "removal of $absolute failed: ${result.stderr().take(200)}" }
    }
}
