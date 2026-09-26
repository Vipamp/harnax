package com.agnetix.harnax.harness.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [DefaultDockerCommandExecutor].
 *
 * These spawn real short-lived subprocesses rather than mocking [Process]: `waitFor(timeout)` +
 * `destroyForcibly` is exactly the behaviour Mockito cannot stub usefully, and the point of the fix is
 * that a process which never exits does not park the caller.
 */
class DefaultDockerCommandExecutorTest {

    private val executor = DefaultDockerCommandExecutor()

    @TempDir
    lateinit var temp: Path

    @Test
    fun `a process that never exits returns the timeout result and is force-destroyed`() {
        // `exec` keeps the same PID as the shell, so the pid we record is the one destroyForcibly kills.
        val pidFile = temp.resolve("wedged.pid")
        val dollar = "${'$'}"
        val command = listOf("sh", "-c", "echo $dollar$dollar > '${pidFile.toAbsolutePath()}'; exec sleep 30")

        val start = System.nanoTime()
        val result = executor.execute(command, 200)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        // A ~200ms budget against a 30s sleep: returning well before 30s is only possible because the
        // wait was bounded and the child destroyed, not because it ran to completion.
        assertEquals(DockerCommandExecutor.TIMEOUT_EXIT_CODE, result.exitCode)
        assertTrue(result.output.contains("timed out"), "expected a timeout message, got: ${result.output}")
        assertTrue(elapsedMs < 5_000, "execute should not block for the 30s sleep, took ${elapsedMs}ms")

        assertTrue(processGone(readPidFile(pidFile)), "subprocess $pidFile survived destroyForcibly")
    }

    @Test
    fun `a normal command still returns its output unchanged`() {
        val result = executor.execute(listOf("echo", "hello world"))

        assertEquals(0, result.exitCode)
        assertEquals("hello world", result.output)
    }

    @Test
    fun `output larger than the pipe buffer is read without eating the budget`() {
        // ~240 KB, well past the roughly 64 KB the kernel holds: a child nobody reads blocks on its next
        // write and never exits, so an unread pipe shows up as a timeout rather than as lost output.
        // `docker build` writes this much routinely.
        val result = executor.execute(listOf("sh", "-c", "yes docker-build-progress-line | head -n 8000"), 10_000)

        assertEquals(0, result.exitCode, "expected a normal exit, got rc=${result.exitCode} output=${result.output.take(120)}")
        assertEquals(8000, result.output.lines().count { it == "docker-build-progress-line" })
    }

    @Test
    fun `an explicit longer budget lets a quick command finish normally`() {
        val result = executor.execute(listOf("echo", "ok"), DockerCommandExecutor.DEFAULT_TIMEOUT_MS)

        assertNotEquals(DockerCommandExecutor.TIMEOUT_EXIT_CODE, result.exitCode)
        assertEquals("ok", result.output)
    }

    private fun readPidFile(pidFile: Path): Long {
        repeat(20) {
            val pid = runCatching { Files.readString(pidFile).trim().toLongOrNull() }.getOrNull()
            if (pid != null) return pid
            Thread.sleep(50)
        }
        throw IllegalStateException("subprocess never wrote its pid to $pidFile")
    }

    /** True once [pid] can no longer be signalled, i.e. the child is dead and reaped. */
    private fun processGone(pid: Long): Boolean {
        repeat(30) {
            val alive = ProcessBuilder("kill", "-0", pid.toString()).start().waitFor() == 0
            if (!alive) return true
            Thread.sleep(50)
        }
        return false
    }
}
