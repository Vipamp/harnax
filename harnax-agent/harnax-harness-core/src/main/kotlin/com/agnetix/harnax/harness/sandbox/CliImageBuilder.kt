package com.agnetix.harnax.harness.sandbox

import com.agnetix.harnax.agent.CliSpec
import com.agnetix.harnax.common.cli.CliPackageLayout
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds (or reuses) Docker sandbox images that carry a set of CLI packages.
 *
 * The image is the payload tree of every selected package, laid onto the filesystem at the paths the
 * package declared, plus the apt packages those payloads asked for. Nothing installs anything at build
 * time from a script: [generateDockerfile] has no `RUN` that executes package content (design I4).
 *
 * The tag is a hash of the base image and each CLI's `payloadDigest`, so it changes when and only when
 * bytes that land in the image change. A package whose `SKILL.md` alone was edited gets a new
 * `packageDigest` and keeps its image — see `CliPackageLayout.payloadDigest`.
 *
 * With zero CLIs the base image is returned unchanged — no build happens.
 */
class CliImageBuilder(
    private val baseImage: String,
    private val packageStore: CliPackageStore,
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
     * @throws IllegalStateException if a spec carries a value no package could register, the payload
     * cannot be fetched, the build fails, or a check command fails
     */
    fun resolveImage(cliSpecs: List<CliSpec>): String {
        if (cliSpecs.isEmpty()) return baseImage

        validate(cliSpecs)
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
     *
     * Each `COPY` names the payload tree admin registered, and its destination is the container root:
     * the sandbox image's `WORKDIR` is `/workspace`, so `./` would drop the whole tree into the agent's
     * working directory instead of onto the filesystem its paths describe.
     */
    fun generateDockerfile(cliSpecs: List<CliSpec>): String = buildString {
        appendLine("FROM $baseImage")
        for (cli in cliSpecs.sortedBy { it.cliId }) {
            appendLine("# CLI: ${cli.name} ${cli.version} (payload sha256:${cli.payloadDigest.take(12)})".trimEnd())
            appendLine("COPY ${cli.packageDigest}/ /")
            val apt = cli.depsApt.distinct().sorted()
            if (apt.isNotEmpty()) {
                appendLine(
                    "RUN apt-get update && apt-get install -y --no-install-recommends ${apt.joinToString(" ")} " +
                        "&& rm -rf /var/lib/apt/lists/*",
                )
            }
        }
    }

    fun combinationHash(cliSpecs: List<CliSpec>): String {
        val material = buildString {
            append(baseImage)
            for (cli in cliSpecs.sortedBy { it.cliId }) {
                append('|').append(cli.cliId)
                append(':').append(cli.version)
                append(':').append(cli.payloadDigest)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    /**
     * Re-checks what admin checked when it registered the package.
     *
     * A spec arrives over the wire and its strings land in a generated Dockerfile — `name` and `version`
     * in a comment, `depsApt` as `apt-get install` arguments — and in directory names under the build
     * context. admin would have refused all of it, but the runtime has no reason to assume the row it
     * was handed was written by the registrar: one newline in `version` would be an extra instruction.
     *
     * Only the offending field is named, never its text, so refusing a value cannot itself write a line
     * into the log.
     */
    private fun validate(cliSpecs: List<CliSpec>) {
        for (cli in cliSpecs) {
            val rejected = buildList {
                if (!CliPackageLayout.NAME_PATTERN.matches(cli.name)) add("name")
                if (!CliPackageLayout.VERSION_PATTERN.matches(cli.version)) add("version")
                if (!CliPackageLayout.DIGEST_PATTERN.matches(cli.packageDigest)) add("packageDigest")
                if (!CliPackageLayout.DIGEST_PATTERN.matches(cli.payloadDigest)) add("payloadDigest")
                if (cli.depsApt.any { !CliPackageLayout.APT_PACKAGE_PATTERN.matches(it) }) add("depsApt")
            }
            if (rejected.isNotEmpty()) {
                throw IllegalStateException(
                    "CLI ${cli.cliId} carries a value no package could have registered: ${rejected.joinToString(", ")}",
                )
            }
        }
    }

    private fun imageExists(tag: String): Boolean = dockerExecutor.execute(listOf("docker", "image", "inspect", tag)).exitCode == 0

    private fun build(
        tag: String,
        cliSpecs: List<CliSpec>,
    ) {
        val cliNames = cliSpecs.joinToString(",") { it.name }
        log.info("[cliImage] Building image {} with CLIs: {}", tag, cliNames)

        val context = Files.createTempDirectory("harnax-cli-image")
        try {
            for (cli in cliSpecs) {
                linkPayloadTree(packageStore.materialize(cli.packageDigest, cli.packageObject), context, cli)
            }
            val dockerfile = context.resolve("Dockerfile")
            Files.writeString(dockerfile, generateDockerfile(cliSpecs))

            val result = dockerExecutor.execute(
                listOf("docker", "build", "-t", tag, "-f", dockerfile.toString(), context.toString()),
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
            context.toFile().deleteRecursively()
        }
    }

    /**
     * Makes one materialized payload tree visible to `docker build` under the name its `COPY` uses.
     *
     * The context is a directory of the trees this one image needs, not the whole package cache: the
     * CLI tars the context on every build, so pointing it at the cache would ship every package this
     * host has ever fetched. Entries are hard links where the filesystem allows it (no second copy of
     * a 40 MB binary) and plain copies where it does not, which is the case whenever the cache sits on a
     * mounted volume and the temp dir does not.
     */
    private fun linkPayloadTree(
        payload: Path,
        context: Path,
        cli: CliSpec,
    ) {
        val target = context.resolve(cli.packageDigest)
        Files.walk(payload).use { tree ->
            tree.forEach { source ->
                val relative = payload.relativize(source)
                val dest = target.resolve(relative.toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(dest)
                } else {
                    Files.createDirectories(dest.parent)
                    try {
                        Files.createLink(source, dest)
                    } catch (e: UnsupportedOperationException) {
                        copyPreservingMode(source, dest)
                    } catch (e: java.io.IOException) {
                        copyPreservingMode(source, dest)
                    }
                }
            }
        }
    }

    private fun copyPreservingMode(
        source: Path,
        dest: Path,
    ) {
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    }
}
