package com.agnetix.harnax.harness.sandbox

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Abstraction for executing Docker CLI commands.
 * Extracted from [KeepAliveSandboxManager] to enable unit testing without a real Docker daemon.
 */
fun interface DockerCommandExecutor {
    /**
     * Executes a Docker command with the [default timeout][DockerCommandExecutor.DEFAULT_TIMEOUT_MS]
     * and returns the result.
     *
     * @param command the full command arguments as a list (e.g. ["docker", "inspect", ...])
     * @return [DockerCommandResult] with exit code and combined stdout+stderr output
     */
    fun execute(command: List<String>): DockerCommandResult

    /**
     * Executes a Docker command bounded by [timeoutMs].
     *
     * Callers whose work is known to run longer than [DEFAULT_TIMEOUT_MS] — an image build, a forced
     * `docker rmi` of a large image — pass a larger budget here rather than relying on the default.
     *
     * The default implementation forwards to [execute] and ignores the budget; only
     * [DefaultDockerCommandExecutor] enforces it. Test doubles that implement just [execute] therefore
     * keep working unchanged.
     *
     * @param command the full command arguments as a list
     * @param timeoutMs how long to wait before the process is force-destroyed
     * @return [DockerCommandResult]; exit code [DockerCommandExecutor.TIMEOUT_EXIT_CODE] if it timed out
     */
    fun execute(
        command: List<String>,
        timeoutMs: Long,
    ): DockerCommandResult = execute(command)

    companion object {
        /**
         * The budget for a no-timeout [execute] call: generous enough for `docker inspect`/`ps`/`start`/
         * `rm -f`/`rmi` under a healthy daemon, yet short enough that a wedged daemon releases the caller
         * in seconds instead of parking the (single, shared) scheduler thread forever.
         */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** `docker build` runs apt-get and copies payload trees; legitimately takes minutes. */
        const val BUILD_TIMEOUT_MS = 30 * 60 * 1000L

        /** A CLI's `checkCommand` runs inside a freshly built image; give it room beyond the default. */
        const val CHECK_TIMEOUT_MS = 5 * 60 * 1000L

        /** `docker rmi -f` of a large image is the slow half of a reclaim sweep (see CliArtifactReaper). */
        const val RMI_TIMEOUT_MS = 5 * 60 * 1000L

        /** Conventional "command timed out" exit code (matches GNU `timeout`), distinct from any docker rc. */
        const val TIMEOUT_EXIT_CODE = 124

        /** How long to wait, after destroyForcibly, for the process to actually die before giving up. */
        const val DEATH_GRACE_MS = 2_000L

        /**
         * How long to wait, after the process exited, for the output pump to see EOF.
         *
         * The child is already gone at that point, so the only reason this can expire is a daemon that
         * holds the pipe open past its child; returning without output beats parking the caller again.
         */
        const val DRAIN_GRACE_MS = 5_000L
    }
}

/**
 * Result of a Docker CLI command execution.
 */
data class DockerCommandResult(
    val exitCode: Int,
    val output: String,
)

/**
 * Default implementation that delegates to [ProcessBuilder].
 *
 * Every wait is bounded: `Process.waitFor()` with no timeout would park the calling thread indefinitely
 * on a wedged Docker daemon, and — because `java.lang.Process.waitFor()` is not interruptible — a caller
 * that cancels the surrounding Future could not recover either. So [execute] uses `waitFor(timeout)`,
 * force-destroys on expiry, and returns a non-zero [DockerCommandResult] that callers already read as
 * "failed" (which, for the reclaim sweeps, means "keep everything").
 */
class DefaultDockerCommandExecutor : DockerCommandExecutor {
    private val log = LoggerFactory.getLogger(DefaultDockerCommandExecutor::class.java)

    override fun execute(command: List<String>): DockerCommandResult = execute(command, DockerCommandExecutor.DEFAULT_TIMEOUT_MS)

    override fun execute(
        command: List<String>,
        timeoutMs: Long,
    ): DockerCommandResult = try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val drained = drainConcurrently(process)
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            destroyOnTimeout(process, command, timeoutMs)
        } else {
            DockerCommandResult(process.exitValue(), awaitOutput(drained).trim())
        }
    } catch (e: Exception) {
        DockerCommandResult(-1, e.message ?: "Unknown error")
    }

    /**
     * Reads the process output on a daemon thread while the caller waits for exit.
     *
     * The pipe holds roughly 64 KB; a command that writes more and is never read blocks on its next
     * write and never exits, so a sequential read-after-[Process.waitFor] would turn a healthy
     * `docker build` into a guaranteed timeout. Draining concurrently is what keeps the budget a
     * budget for output volume rather than for runtime.
     */
    private fun drainConcurrently(process: Process): CompletableFuture<String> {
        val out = CompletableFuture<String>()
        val pump = Thread {
            try {
                out.complete(process.inputStream.bufferedReader().readText())
            } catch (e: Exception) {
                out.completeExceptionally(e)
            }
        }
        pump.isDaemon = true
        pump.name = "docker-exec-drain"
        pump.start()
        return out
    }

    /**
     * The already-read output, waiting only long enough for the pump to hit the EOF a dead process leaves.
     */
    private fun awaitOutput(drained: CompletableFuture<String>): String = try {
        drained.get(DockerCommandExecutor.DRAIN_GRACE_MS, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        log.warn("[dockerExec] output could not be read after the process exited: {}", e.message)
        ""
    }

    /**
     * Force-destroys a process that outlived its budget and returns the timeout result.
     *
     * The output pump is not joined here: closing the stream kills it, and waiting on a pipe a wedged
     * daemon may never close is the exact hang this method exists to escape.
     */
    private fun destroyOnTimeout(
        process: Process,
        command: List<String>,
        timeoutMs: Long,
    ): DockerCommandResult {
        process.destroyForcibly()
        val died = process.waitFor(DockerCommandExecutor.DEATH_GRACE_MS, TimeUnit.MILLISECONDS)
        val subcommand = command.getOrNull(1) ?: command.firstOrNull() ?: "unknown"
        log.warn(
            "[dockerExec] docker '{}' did not finish within {} ms; force-destroyed (died={}, argCount={}, args={})",
            subcommand,
            timeoutMs,
            died,
            command.size,
            summarize(command),
        )
        return DockerCommandResult(
            DockerCommandExecutor.TIMEOUT_EXIT_CODE,
            "Docker '$subcommand' timed out after ${timeoutMs}ms and was destroyed",
        )
    }

    /**
     * A bounded, credential-scrubbed summary of [command] for the WARN log.
     *
     * Docker argv can carry registry references of the form `scheme://user:token@host/...`, so the raw
     * argv must never reach the log. Any URL userinfo is masked and the remainder is length-capped.
     */
    private fun summarize(command: List<String>): String {
        val masked = command.joinToString(" ").replace(Regex("://[^/\\s]*@"), "://***@")
        return if (masked.length > MAX_SUMMARY_CHARS) masked.take(MAX_SUMMARY_CHARS) + "…" else masked
    }

    private companion object {
        const val MAX_SUMMARY_CHARS = 200
    }
}
