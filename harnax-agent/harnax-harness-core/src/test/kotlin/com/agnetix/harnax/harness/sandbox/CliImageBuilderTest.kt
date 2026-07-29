package com.agnetix.harnax.harness.sandbox

import com.agnetix.harnax.agent.CliSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CliImageBuilder].
 *
 * Uses a scripted [DockerCommandExecutor] to test image resolution and build
 * logic without requiring a real Docker daemon.
 */
class CliImageBuilderTest {

    private val kubectl = CliSpec(
        cliId = 1,
        name = "kubectl",
        version = "1.30.0",
        installScript = "curl -LO https://dl.k8s.io/release/v1.30.0/bin/linux/amd64/kubectl && install kubectl /usr/local/bin/",
        checkCommand = "kubectl version --client",
    )

    private val gh = CliSpec(
        cliId = 2,
        name = "gh",
        version = "2.50.0",
        installScript = "apt-get update && apt-get install -y gh",
        checkCommand = "gh --version",
    )

    /** Records executed commands and returns scripted results by command prefix. */
    private class ScriptedExecutor(
        private val handler: (List<String>) -> DockerCommandResult,
    ) : DockerCommandExecutor {
        val commands = mutableListOf<List<String>>()
        override fun execute(command: List<String>): DockerCommandResult {
            commands.add(command)
            return handler(command)
        }
    }

    @Test
    fun `zero CLIs returns base image without any docker call`() {
        val executor = ScriptedExecutor { DockerCommandResult(0, "") }
        val builder = CliImageBuilder("python:3.11-slim", executor)

        assertEquals("python:3.11-slim", builder.resolveImage(emptyList()))
        assertTrue(executor.commands.isEmpty())
    }

    @Test
    fun `combination hash is stable and order independent`() {
        val builder = CliImageBuilder("python:3.11-slim")

        val hash1 = builder.combinationHash(listOf(kubectl, gh))
        val hash2 = builder.combinationHash(listOf(gh, kubectl))
        assertEquals(hash1, hash2)
        assertEquals(12, hash1.length)
    }

    @Test
    fun `combination hash changes when install script changes`() {
        val builder = CliImageBuilder("python:3.11-slim")

        val hash1 = builder.combinationHash(listOf(kubectl))
        val hash2 = builder.combinationHash(listOf(kubectl.copy(installScript = "echo changed")))
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `combination hash changes when base image changes`() {
        val hash1 = CliImageBuilder("python:3.11-slim").combinationHash(listOf(kubectl))
        val hash2 = CliImageBuilder("ubuntu:24.04").combinationHash(listOf(kubectl))
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `generateDockerfile contains base image and sorted RUN fragments`() {
        val builder = CliImageBuilder("python:3.11-slim")

        val dockerfile = builder.generateDockerfile(listOf(gh, kubectl))
        val lines = dockerfile.lines()

        assertEquals("FROM python:3.11-slim", lines[0])
        // Sorted by cliId: kubectl (1) before gh (2)
        assertTrue(dockerfile.indexOf("RUN ${kubectl.installScript}") < dockerfile.indexOf("RUN ${gh.installScript}"))
    }

    @Test
    fun `existing image is reused without build`() {
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(0, "found")
                else -> DockerCommandResult(1, "unexpected: $cmd")
            }
        }
        val builder = CliImageBuilder("python:3.11-slim", executor)

        val tag = builder.resolveImage(listOf(kubectl))
        assertTrue(tag.startsWith("harnax-sandbox:cli-"))
        assertTrue(executor.commands.none { it.getOrNull(1) == "build" })
    }

    @Test
    fun `missing image triggers build and check commands`() {
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(1, "not found")
                cmd.getOrNull(1) == "build" -> DockerCommandResult(0, "built")
                cmd.getOrNull(1) == "run" -> DockerCommandResult(0, "ok")
                else -> DockerCommandResult(1, "unexpected: $cmd")
            }
        }
        val builder = CliImageBuilder("python:3.11-slim", executor)

        val tag = builder.resolveImage(listOf(kubectl, gh))
        assertTrue(tag.startsWith("harnax-sandbox:cli-"))
        assertEquals(1, executor.commands.count { it.getOrNull(1) == "build" })
        // One check per CLI with a non-blank checkCommand
        assertEquals(2, executor.commands.count { it.getOrNull(1) == "run" })
    }

    @Test
    fun `build failure throws with CLI names in message`() {
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(1, "not found")
                cmd.getOrNull(1) == "build" -> DockerCommandResult(1, "network unreachable")
                else -> DockerCommandResult(0, "")
            }
        }
        val builder = CliImageBuilder("python:3.11-slim", executor)

        val ex = assertThrows(IllegalStateException::class.java) {
            builder.resolveImage(listOf(kubectl))
        }
        assertTrue(ex.message!!.contains("kubectl"))
    }

    @Test
    fun `failed check command removes image and throws`() {
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(1, "not found")
                cmd.getOrNull(1) == "build" -> DockerCommandResult(0, "built")
                cmd.getOrNull(1) == "run" -> DockerCommandResult(127, "command not found")
                else -> DockerCommandResult(0, "")
            }
        }
        val builder = CliImageBuilder("python:3.11-slim", executor)

        val ex = assertThrows(IllegalStateException::class.java) {
            builder.resolveImage(listOf(kubectl))
        }
        assertTrue(ex.message!!.contains("kubectl"))
        assertTrue(executor.commands.any { it.getOrNull(1) == "rmi" })
    }

    @Test
    fun `blank check command is skipped`() {
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(1, "not found")
                cmd.getOrNull(1) == "build" -> DockerCommandResult(0, "built")
                else -> DockerCommandResult(1, "unexpected: $cmd")
            }
        }
        val builder = CliImageBuilder("python:3.11-slim", executor)

        builder.resolveImage(listOf(kubectl.copy(checkCommand = "")))
        assertFalse(executor.commands.any { it.getOrNull(1) == "run" })
    }
}
