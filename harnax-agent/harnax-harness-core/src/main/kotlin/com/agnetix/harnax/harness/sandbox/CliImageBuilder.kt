package com.agnetix.harnax.harness.sandbox

import com.agnetix.harnax.agent.CliSpec
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds (or reuses) Docker sandbox images that bundle a set of CLI tools.
 *
 * The image tag is derived from a hash of the base image plus each CLI's
 * id/version/installScript, so the same CLI combination always maps to the
 * same tag and the build is skipped when the image already exists locally.
 *
 * With zero CLIs the base image is returned unchanged — no build happens.
 */
class CliImageBuilder(
    private val baseImage: String,
    private val dockerExecutor: DockerCommandExecutor = DefaultDockerCommandExecutor(),
) {

    private val log = LoggerFactory.getLogger(CliImageBuilder::class.java)

    // Serializes concurrent builds of the same tag within this JVM
    private val buildLocks = ConcurrentHashMap<String, Any>()

    // Tags confirmed to exist locally; avoids a `docker image inspect` subprocess per agent creation
    private val knownImages = ConcurrentHashMap.newKeySet<String>()

    /**
     * Resolves the sandbox image for the given CLI set, building it if necessary.
     *
     * @return the image tag to use for the sandbox container
     * @throws IllegalStateException if the image build or a check command fails
     */
    fun resolveImage(cliSpecs: List<CliSpec>): String {
        if (cliSpecs.isEmpty()) return baseImage

        val sorted = cliSpecs.sortedBy { it.cliId }
        val tag = "harnax-sandbox:cli-${combinationHash(sorted)}"

        if (tag in knownImages) return tag
        if (imageExists(tag)) {
            log.debug("[cliImage] Reusing existing image {}", tag)
            knownImages.add(tag)
            return tag
        }

        synchronized(buildLocks.computeIfAbsent(tag) { Any() }) {
            if (tag !in knownImages && !imageExists(tag)) {
                build(tag, sorted)
            }
            knownImages.add(tag)
        }
        return tag
    }

    /**
     * Generates the Dockerfile content for the given CLI set.
     */
    fun generateDockerfile(cliSpecs: List<CliSpec>): String = buildString {
        appendLine("FROM $baseImage")
        for (cli in cliSpecs.sortedBy { it.cliId }) {
            appendLine("# CLI: ${cli.name} ${cli.version}".trimEnd())
            appendLine("RUN ${cli.installScript.trim()}")
        }
    }

    fun combinationHash(cliSpecs: List<CliSpec>): String {
        val material = buildString {
            append(baseImage)
            for (cli in cliSpecs.sortedBy { it.cliId }) {
                append('|').append(cli.cliId)
                append(':').append(cli.version)
                append(':').append(cli.installScript)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun imageExists(tag: String): Boolean = dockerExecutor.execute(listOf("docker", "image", "inspect", tag)).exitCode == 0

    private fun build(tag: String, cliSpecs: List<CliSpec>) {
        val cliNames = cliSpecs.joinToString(",") { it.name }
        log.info("[cliImage] Building image {} with CLIs: {}", tag, cliNames)

        val buildDir = Files.createTempDirectory("harnax-cli-image")
        try {
            val dockerfile = buildDir.resolve("Dockerfile")
            Files.writeString(dockerfile, generateDockerfile(cliSpecs))

            val result = dockerExecutor.execute(
                listOf("docker", "build", "-t", tag, "-f", dockerfile.toString(), buildDir.toString()),
            )
            if (result.exitCode != 0) {
                throw IllegalStateException(
                    "Failed to build CLI sandbox image $tag (CLIs: $cliNames): ${result.output.takeLast(2000)}",
                )
            }

            // Verify each CLI installation inside the freshly built image
            for (cli in cliSpecs) {
                if (cli.checkCommand.isBlank()) continue
                val check = dockerExecutor.execute(
                    listOf("docker", "run", "--rm", "--entrypoint", "/bin/sh", tag, "-c", cli.checkCommand),
                )
                if (check.exitCode != 0) {
                    dockerExecutor.execute(listOf("docker", "rmi", "-f", tag))
                    throw IllegalStateException(
                        "CLI '${cli.name}' check command failed in image $tag: ${check.output.takeLast(500)}",
                    )
                }
            }
            log.info("[cliImage] Image {} built and verified", tag)
        } finally {
            buildDir.toFile().deleteRecursively()
        }
    }
}
