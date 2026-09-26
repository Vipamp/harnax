package com.agnetix.harnax.agent.service.sandbox

import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.agent.toCliSpec
import com.agnetix.harnax.entity.dto.CliDetailDto
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.SandboxConfig
import com.agnetix.harnax.harness.sandbox.CliImageBuilder
import com.agnetix.harnax.harness.sandbox.CliPackageStore
import com.agnetix.harnax.harness.sandbox.DockerCommandExecutor
import com.agnetix.harnax.harness.sandbox.DockerCommandResult
import com.sun.net.httpserver.HttpServer
import io.minio.MinioClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

/**
 * One reclaim round on the real path: HTTP in, files and images out.
 *
 * [CliArtifactReaperTest] pins the reaper's decisions with a stubbed client and a stubbed store, and
 * `CliPackageStoreTest` / `CliImageBuilderTest` pin each sweep on its own. What no test there can see is the
 * seam between them: `AdminApiClient.getCliPackageInventory` had never answered from a socket, and admin's
 * `/api/admin/internal/cli/inventory` had never been read by the code that acts on it. A field the client
 * dropped, or a digest that reached the wrong sweep, was invisible from either side alone — and the second
 * half is the expensive kind of wrong, because a missed entry in the whitelist is a deletion.
 *
 * So the client, the payload cache and the image sweep all run for real here, against a JDK HTTP server
 * serving admin's response shape written out as the literal JSON the wire carries. Only the `docker` CLI is
 * doubled, and the doubling is a recorder: it says what the daemon would have said and reports what it was
 * asked to remove.
 */
class CliArtifactReaperRoundTest {

    @TempDir
    lateinit var cacheDir: Path

    private lateinit var server: HttpServer

    /** Read at bind time: the second case stops the server before it constructs the client. */
    private var adminPort: Int = 0
    private val adminUrl: String
        get() = "http://127.0.0.1:$adminPort"

    private val removedImages = mutableListOf<String>()

    private val inUseDigest = "a".repeat(64)
    private val staleDigest = "b".repeat(64)
    private val freshDigest = "c".repeat(64)

    /** Feeds the tag formula and the fake's `docker images` output alike. */
    private val baseImage = "python:3.11-slim"

    private val inUseCli = CliDetailDto(
        id = 21L,
        name = "kubectl",
        version = "1.30.0",
        packageDigest = inUseDigest,
        payloadDigest = "d".repeat(64),
    )

    /** A CLI set admin no longer registers: its image is the one the sweep must find. */
    private val goneCli = inUseCli.copy(id = 99L, name = "old-tool", payloadDigest = "e".repeat(64))

    /**
     * Read lazily, from inside the fake: both the tag formula and the builder that owns it come from the
     * [builder] this executor is handed to, so computing a tag any earlier is reading it unbuilt.
     */
    private val liveTag: String
        get() = builder.tagOf(listOf(inUseCli.toCliSpec()))

    private val goneTag: String
        get() = builder.tagOf(listOf(goneCli.toCliSpec()))

    private lateinit var builder: CliImageBuilder

    @BeforeEach
    fun startAdmin() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        adminPort = server.address.port
        server.createContext("/api/admin/internal/cli/inventory") { exchange ->
            val body = inventoryJson().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stopAdmin() {
        server.stop(0)
    }

    /**
     * admin's `ResultVo<CliPackageInventoryResponse>` as the wire carries it. Written out rather than
     * serialised from the DTO, because a hand-written shape is the only way this test can disagree with
     * whatever the server's own field names happen to be.
     */
    private fun inventoryJson(): String = """
        {
          "code": 200,
          "message": "success",
          "timestamp": 1770000000000,
          "data": {
            "packageDigests": ["$inUseDigest"],
            "agentCliSets": [
              {
                "agentId": 100,
                "clis": [
                  {
                    "id": 21,
                    "name": "kubectl",
                    "version": "1.30.0",
                    "checkCommand": "kubectl version --client",
                    "packageObject": "$inUseDigest",
                    "packageDigest": "$inUseDigest",
                    "payloadDigest": "${"d".repeat(64)}",
                    "depsApt": [],
                    "runtimeEnv": {},
                    "envBindings": [],
                    "skill": null
                  }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    private fun cachedAt(
        digest: String,
        age: Duration,
    ) {
        val tree = cacheDir.resolve(digest)
        Files.createDirectories(tree.resolve("usr/local/bin"))
        Files.writeString(tree.resolve("usr/local/bin/tool"), "#!/bin/sh\n")
        val marker = cacheDir.resolve("$digest.complete")
        Files.writeString(marker, digest)
        val stamp = FileTime.from(Instant.now().minus(age))
        Files.setLastModifiedTime(tree, stamp)
        Files.setLastModifiedTime(marker, stamp)
    }

    private fun docker(): DockerCommandExecutor {
        val built = Instant.now().minus(Duration.ofHours(3)).toString()
        return DockerCommandExecutor { command ->
            when {
                command == listOf("docker", "ps", "-a", "--format", "{{.Image}}") -> DockerCommandResult(0, "")
                command == listOf("docker", "images", "--format", "{{.Repository}}:{{.Tag}}") ->
                    DockerCommandResult(0, "$liveTag\n$goneTag\n$baseImage\n")

                command.size >= 5 && command[1] == "image" && command[2] == "inspect" -> DockerCommandResult(0, built)
                command.size == 3 && command[1] == "rmi" -> {
                    removedImages += command[2]
                    DockerCommandResult(0, "Untagged: ${command[2]}")
                }

                else -> DockerCommandResult(1, "unexpected docker command: $command")
            }
        }
    }

    private fun runRound(): CliArtifactReaper {
        builder = CliImageBuilder(
            baseImage = baseImage,
            packageStore = CliPackageStore(mock<MinioClient>(), "cli-packages", cacheDir),
            dockerExecutor = docker(),
        )
        val launcher = mock<HarnessAgentLauncher>()
        whenever(launcher.cliImageBuilder).thenReturn(builder)
        whenever(launcher.harnessConfig).thenReturn(
            HarnessConfig(sandbox = SandboxConfig(cliReclaimGraceMinutes = 60)),
        )
        val client = AdminApiClient(adminUrl, "it-secret")
        return CliArtifactReaper(launcher, client, Duration.ofHours(1).toMillis(), 0L, Duration.ofSeconds(30).toMillis())
    }

    @Test
    @DisplayName("one round over HTTP drops the stale tree and image and keeps what admin still names")
    fun roundReclaimsThroughTheRealClientAndStore() {
        cachedAt(inUseDigest, Duration.ofHours(3))
        cachedAt(staleDigest, Duration.ofHours(3))
        cachedAt(freshDigest, Duration.ofMinutes(1))
        val reaper = runRound()

        reaper.reclaim()
        reaper.shutdown()

        // The whitelist came from the socket: only what admin named was spared, and only after its grace.
        assertTrue(Files.isDirectory(cacheDir.resolve(inUseDigest)), "an in-use payload tree must survive")
        assertFalse(Files.exists(cacheDir.resolve(staleDigest)), "an unregistered tree past grace is disk nobody can reuse")
        assertFalse(Files.exists(cacheDir.resolve("$staleDigest.complete")))
        assertTrue(Files.isDirectory(cacheDir.resolve(freshDigest)), "inside the grace window the tree stays")
        assertTrue(Files.exists(cacheDir.resolve("$freshDigest.complete")))

        // And the image sweep ran on the same answer, by the same tag formula the build path uses.
        assertEquals(listOf(goneTag), removedImages, "only the set admin no longer registers may go")
        assertFalse(liveTag in removedImages)
    }

    @Test
    @DisplayName("an inventory admin refuses to answer costs nothing")
    fun roundKeepsEverythingWhenAdminDoesNotAnswer() {
        cachedAt(staleDigest, Duration.ofHours(3))
        server.stop(0)
        val reaper = runRound()

        reaper.reclaim()
        reaper.shutdown()

        assertTrue(Files.isDirectory(cacheDir.resolve(staleDigest)), "no answer is not an licence to delete")
        assertEquals(emptyList<String>(), removedImages, "the image sweep must not even be reached")
    }
}
