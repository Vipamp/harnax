package com.agnetix.harnax.common.cli

import java.io.File
import java.security.MessageDigest

/**
 * The shape a CLI plugin package must have, and the digests derived from it.
 *
 * Lives in `harnax-common` because both halves of the chain have to agree on it byte for byte: admin
 * computes the digests when it registers a package and hands them to agent-service, which later
 * rebuilds the payload tree and names its image after [payloadDigest]. Two implementations that
 * drift produce images that silently fail to match what was registered, which is the one failure
 * mode this design cannot afford.
 */
object CliPackageLayout {
    /** File name suffix a package must carry to be picked up from the package directory. */
    const val FILE_SUFFIX = ".harnaxcli.zip"

    const val MANIFEST_ENTRY = "plugin.yaml"
    const val SKILL_ENTRY = "skill/SKILL.md"
    const val SKILL_ASSET_PREFIX = "skill/assets/"
    const val PAYLOAD_PREFIX = "payload/"

    /** `name` is the identity across versions (design I1), so it is also a file-name-safe slug. */
    val NAME_PATTERN = Regex("^[a-z][a-z0-9-]{1,63}$")

    /**
     * Manifest version alphabet. Kept here rather than in the parser because the runtime re-checks it:
     * the string reaches a generated Dockerfile, so a newline in it would be an instruction.
     */
    val VERSION_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$")

    /**
     * apt package names, with an optional pinned version.
     *
     * The list is interpolated into `RUN apt-get install -y …` verbatim, so anything outside this
     * alphabet — spaces, semicolons, `$`, backquotes, a leading `-` that would read as an apt option —
     * is rejected as a set of characters rather than as a shell-quoting problem.
     */
    val APT_PACKAGE_PATTERN = Regex("^[a-z0-9][a-z0-9+.-]{0,62}(=[0-9A-Za-z.+-]{1,32})?$")

    /** What a sha256 hex digest looks like: both digests are file names and object-key material. */
    val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")

    /**
     * Payload destinations that would let a package own the host or the sandbox's own controls.
     *
     * This is defence in depth, not a sandbox (design risk 1): whoever can drop a file in the
     * package directory already decides what runs as root in every agent container.
     */
    val DENIED_PAYLOAD_TARGETS = listOf(
        "etc/",
        "root/",
        "usr/bin/docker",
        "usr/local/bin/docker",
        "bin/su",
        "usr/bin/su",
        "sbin/",
        "usr/sbin/",
        "var/run/docker.sock",
    )

    const val MAX_PAYLOAD_FILES = 2000
    const val MAX_PAYLOAD_BYTES = 512L * 1024 * 1024

    /**
     * Validate one payload destination — a path relative to the container root, as it appears under
     * `payload/` in the archive.
     *
     * The path has to be spelled canonically. `usr//bin/su`, `./etc/passwd` and `usr/./bin/docker` all
     * describe a destination the deny list below would wave through as text while [resolveInside]
     * normalises them straight onto it. One spelling is also what keeps the digests honest:
     * `payload/./usr/bin/x` and `payload/usr/bin/x` are the same file written twice but two entries
     * hashed, so the image tag and the image contents would stop meaning the same thing.
     *
     * @return null when the path is acceptable, otherwise the reason to report to the log
     */
    fun checkPayloadPath(path: String): String? {
        checkRelativePath(path)?.let { return it }
        val segments = path.split("/")
        DENIED_PAYLOAD_TARGETS.forEach { denied ->
            val target = denied.trimEnd('/').split("/")
            val landsOnTarget = if (denied.endsWith("/")) {
                segments.size >= target.size && segments.subList(0, target.size) == target
            } else {
                segments == target
            }
            if (landsOnTarget) return "payload path lands on a denied target: $path"
        }
        return null
    }

    /**
     * Spell [path] as one canonical relative path, or say why it is not one.
     *
     * This is the whole anti-traversal rule for a package's internal names, and it is shared because
     * payload destinations and skill resource keys both end up as paths someone later resolves under
     * a root: an entry that means two spellings of one location is enough for a check at the door to
     * disagree with the code that writes the file.
     */
    fun checkRelativePath(path: String): String? {
        if (path.isBlank()) return "empty path"
        if (path.startsWith("/")) return "path must be relative: $path"
        if (path.contains("\u0000")) return "path contains a NUL byte"
        path.split("/").forEach { segment ->
            when {
                segment.isEmpty() -> return "path has an empty segment: $path"
                segment == "." -> return "path is not in canonical form: $path"
                segment == ".." -> return "path escapes its root: $path"
                segment.contains(":") -> return "path contains a colon: $path"
            }
        }
        return null
    }

    /** Resolve [relative] under [root], refusing anything that lands outside it. */
    fun resolveInside(
        root: File,
        relative: String,
    ): File {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val target = rootPath.resolve(relative).normalize()
        if (!target.startsWith(rootPath)) {
            throw IllegalArgumentException("Package path escapes its target directory: $relative")
        }
        return target.toFile()
    }

    /** sha256 of the archive as stored — the package's version identity (design I2). */
    fun packageDigest(file: File): String = file.inputStream().use { sha256Hex(it.readBytes()) }

    /**
     * Canonical digest of what actually lands in the image: the payload tree plus the declared apt
     * dependencies (design D15 / I7).
     *
     * Deliberately insensitive to anything else in the package — timestamps, entry order, the skill
     * text, `description` wording — because a container is rebuilt when and only when this changes.
     * A package whose `SKILL.md` was edited therefore registers a new [packageDigest] (fresh object,
     * updated row, new prompt for the next session) while the running sandboxes keep their image.
     *
     * Each entry contributes `path|mode|sha256(content)`; entries are sorted by path so the packing
     * order cannot leak in.
     */
    fun payloadDigest(
        archive: CliPackageArchive,
        depsApt: List<String>,
    ): String {
        val lines = archive.entriesUnder(PAYLOAD_PREFIX)
            .filterNot { it.directory }
            .map { entry ->
                val relative = entry.archivePath.removePrefix(PAYLOAD_PREFIX)
                "$relative|${entry.mode}|${sha256Hex(archive.readBytes(entry.archivePath))}"
            }
            .sorted()
        val canonical = lines.joinToString("\n") + "\n#apt:" + depsApt.sorted().joinToString(",")
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
