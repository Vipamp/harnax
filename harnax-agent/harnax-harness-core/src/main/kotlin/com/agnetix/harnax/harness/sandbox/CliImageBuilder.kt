package com.agnetix.harnax.harness.sandbox

import com.agnetix.harnax.agent.CliSpec
import com.agnetix.harnax.common.cli.CliPackageLayout
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
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
    /**
     * The payload trees this host has cached for the packages it can build images from. Public because
     * they are reclaimed on the same inventory the images are: a tree no registered package names is the
     * one thing a rebuild cannot recover once admin drops the archive.
     */
    val packageStore: CliPackageStore,
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
        val tag = tagOf(sorted)

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
     * The image tag one CLI set builds to.
     *
     * This is the only place the tag is assembled, because [evictUnusedImages] decides what to delete with
     * it: a second copy of the formula that ever disagreed with the build path would remove the image a
     * live agent is about to start from.
     */
    fun tagOf(cliSpecs: List<CliSpec>): String = "$TAG_PREFIX${combinationHash(cliSpecs.sortedBy { it.cliId })}"

    /**
     * Removes the sandbox images this host has built for CLI sets that no longer exist.
     *
     * One image per CLI combination, and a combination changes whenever an agent's CLI selection or one of
     * its packages' payloads changes, so the set of images on disk is a history of choices nobody makes any
     * more. Three things have to hold before one is dropped:
     *
     * - no live CLI set builds to this tag — the whitelist comes from admin's binding inventory, judged by
     *   the same [tagOf] the build path uses;
     * - no container references it, running or exited — `docker ps -a` covers the keep-alive sandboxes,
     *   which are stopped rather than removed;
     * - it was built outside [grace] — an image built seconds ago belongs to a session whose container is
     *   not there yet, and whose spec may predate the binding change this whitelist reflects.
     *
     * Anything the daemon refuses to answer aborts the sweep rather than narrowing it: the deletion is the
     * irreversible half, so `docker ps` failing means nothing is removed. Tags outside this platform's own
     * namespace are never candidates, which is what keeps the base image out of reach.
     *
     * @param liveCliSets each agent's configured CLIs, as admin's inventory reports them
     * @return how many images were removed
     */
    fun evictUnusedImages(
        liveCliSets: List<List<CliSpec>>,
        grace: Duration,
    ): Int {
        val referenced = dockerExecutor.execute(listOf("docker", "ps", "-a", "--format", "{{.Image}}"))
        if (referenced.exitCode != 0) {
            log.warn("[cliImage] Keeping every CLI image: docker could not list containers ({})", referenced.output.take(200))
            return 0
        }
        val listings = dockerExecutor.execute(listOf("docker", "images", "--format", "{{.Repository}}:{{.Tag}}"))
        if (listings.exitCode != 0) {
            log.warn("[cliImage] Keeping every CLI image: docker could not list images ({})", listings.output.take(200))
            return 0
        }
        val live = liveCliSets.map { tagOf(it) }.toSet()
        val inUse = referenced.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val cutoff = Instant.now().minus(grace)
        var removed = 0
        for (tag in listings.output.lineSequence().map { it.trim() }.filter { it.startsWith(TAG_PREFIX) }.toList()) {
            if (tag in live || tag in inUse) continue
            val built = buildTimeOf(tag)
            if (built == null) {
                log.warn("[cliImage] Keeping {}: its build time could not be read", tag)
                continue
            }
            if (built.isAfter(cutoff)) continue
            val rmi = dockerExecutor.execute(listOf("docker", "rmi", tag))
            if (rmi.exitCode != 0) {
                log.warn("[cliImage] {} could not be removed: {}", tag, rmi.output.take(200))
                continue
            }
            // The cache would otherwise keep answering that an image this host no longer has is present,
            // and the next agent would fail at `docker create` instead of rebuilding here.
            knownImages.remove(tag)
            removed++
            log.info("[cliImage] Removed unused CLI image {}", tag)
        }
        return removed
    }

    private fun buildTimeOf(tag: String): Instant? {
        val inspected = dockerExecutor.execute(listOf("docker", "image", "inspect", "--format", "{{.Created}}", tag))
        if (inspected.exitCode != 0) return null
        return runCatching { Instant.parse(inspected.output.trim()) }.getOrNull()
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

    companion object {
        /**
         * The namespace of every image this class builds. [evictUnusedImages] scopes itself to it, which is
         * what keeps the base image — and everything else on this host — out of reach.
         */
        private const val TAG_PREFIX = "harnax-sandbox:cli-"
    }
}
