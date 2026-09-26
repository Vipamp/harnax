package com.agnetix.harnax.harness.sandbox

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.sandbox.ExecResult
import io.agentscope.harness.agent.sandbox.Sandbox
import io.agentscope.harness.agent.sandbox.SandboxState
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64

/**
 * A [Sandbox] whose `exec` is a miniature shell over an in-memory filesystem, for tests that care about
 * *which* commands a write path issues and what is on disk afterwards.
 *
 * It understands exactly the commands [SandboxFileWriter] emits — `mkdir -p $(dirname …)`,
 * `printf %s … >> …`, `base64 -d … > … && rm -f …`, `base64 …`, `rm -f …`, `rm -rf …` — and fails the
 * test on anything else. A permissive fake that answers `exit 0` to a command nobody sent is how a
 * write path keeps passing while the real one is broken, so [commands] stays a full record and
 * [files] the observable result of running it.
 *
 * `rm -rf` removes the prefix it names, which is what lets a test assert that a whole skill directory
 * is gone rather than that one particular command was issued.
 *
 * @param failWhen a command this sandbox refuses to run, throwing as a container whose exec dies
 * @param refusesWhen a command that runs but exits non-zero, as a failing `base64 -d` does
 */
class VirtualSandbox(
    private val failWhen: (String) -> Boolean = { false },
    private val refusesWhen: (String) -> Boolean = { false },
) : Sandbox {

    /** Every command passed to [exec], in order. */
    val commands = mutableListOf<String>()

    /** File contents by absolute path once [commands] have run. */
    val files = linkedMapOf<String, String>()

    /** The bytes accumulated in a temporary file that has not been decoded yet. */
    val pendingTemps: Set<String> get() = temps.keys.toSet()

    private val temps = linkedMapOf<String, StringBuilder>()

    /** Paths removed by the last `rm -rf`, so a test can name what disappeared. */
    val removedDirectories = mutableListOf<String>()

    private val mkdir = Regex("""mkdir -p \$\(dirname '([^']+)'\)""")
    private val append = Regex("""printf %s '([^']*)' >> '([^']+)'""")
    private val decode = Regex("""base64 -d '([^']+)' > '([^']+)' && rm -f '([^']+)'""")
    private val encode = Regex("""base64 '([^']+)'""")
    private val removeFile = Regex("""rm -f '([^']+)'""")
    private val removeTree = Regex("""rm -rf '([^']+)'""")

    /** Absolute paths under [prefix], as they sit on disk. */
    fun pathsUnder(prefix: String): List<String> = files.keys.filter { it.startsWith(prefix) }.sorted()

    override fun exec(
        runtimeContext: RuntimeContext?,
        command: String,
        timeoutSeconds: Int?,
    ): ExecResult {
        commands += command
        if (failWhen(command)) throw IOException("simulated container failure on: $command")
        if (refusesWhen(command)) return ExecResult(1, "", "simulated failure of: $command", false)
        return when {
            mkdir.matches(command) -> OK

            append.matches(command) -> {
                val groups = append.find(command)!!.groupValues
                temps.getOrPut(groups[2]) { StringBuilder() }.append(groups[1])
                OK
            }

            decode.matches(command) -> {
                val groups = decode.find(command)!!.groupValues
                val temp = groups[1]
                val target = groups[2]
                val accumulated = temps.remove(temp)
                if (accumulated == null) {
                    // Real `base64 -d` on a missing input exits 1, and the redirect has already
                    // truncated the target — reproduce both halves so the failure is visible.
                    files.remove(target)
                    ExecResult(1, "", "base64: $temp: No such file or directory", false)
                } else {
                    files[target] = String(Base64.getMimeDecoder().decode(accumulated.toString()), Charsets.UTF_8)
                    temps.remove(groups[3])
                    OK
                }
            }

            encode.matches(command) -> {
                val path = encode.find(command)!!.groupValues[1]
                val content = files[path]
                if (content == null) {
                    ExecResult(1, "", "base64: $path: No such file or directory", false)
                } else {
                    ExecResult(0, Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)), "", false)
                }
            }

            removeFile.matches(command) -> {
                val path = removeFile.find(command)!!.groupValues[1]
                files.remove(path)
                temps.remove(path)
                OK
            }

            removeTree.matches(command) -> {
                val prefix = removeTree.find(command)!!.groupValues[1]
                removedDirectories += prefix
                files.keys.filter { it == prefix || it.startsWith("$prefix/") }.forEach { files.remove(it) }
                temps.keys.filter { it.startsWith(prefix) }.forEach { temps.remove(it) }
                OK
            }

            else -> throw AssertionError("VirtualSandbox was asked to run a command it does not model: $command")
        }
    }

    override fun start() {}

    override fun stop() {}

    override fun close() {}

    override fun isRunning(): Boolean = true

    override fun getState(): SandboxState = object : SandboxState() {}

    override fun persistWorkspace(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun hydrateWorkspace(archive: InputStream?) {}

    private companion object {
        val OK = ExecResult(0, "", "", false)
    }
}
