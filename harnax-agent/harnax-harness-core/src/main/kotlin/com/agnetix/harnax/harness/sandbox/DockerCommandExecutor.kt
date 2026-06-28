package com.agnetix.harnax.harness.sandbox

/**
 * Abstraction for executing Docker CLI commands.
 * Extracted from [KeepAliveSandboxManager] to enable unit testing without a real Docker daemon.
 */
fun interface DockerCommandExecutor {
    /**
     * Executes a Docker command and returns the result.
     *
     * @param command the full command arguments as a list (e.g. ["docker", "inspect", ...])
     * @return [DockerCommandResult] with exit code and combined stdout+stderr output
     */
    fun execute(command: List<String>): DockerCommandResult
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
 */
class DefaultDockerCommandExecutor : DockerCommandExecutor {
    override fun execute(command: List<String>): DockerCommandResult = try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        val output = process.inputStream.bufferedReader().readText().trim()
        DockerCommandResult(process.exitValue(), output)
    } catch (e: Exception) {
        DockerCommandResult(-1, e.message ?: "Unknown error")
    }
}
