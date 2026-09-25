package com.agnetix.harnax.harness.sandbox

import com.agnetix.harnax.common.cli.CliPackageArchive
import com.agnetix.harnax.common.cli.CliPackageLayout
import io.minio.GetObjectArgs
import io.minio.MinioClient
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Keeps the payload of a CLI package on local disk, keyed by its [CliPackageLayout.packageDigest].
 *
 * admin stored the archive in MinIO when it registered it and hands the same digest down with the agent
 * spec, so this is a pure fetch-and-unpack step: the digest says both *which* object to read and whether
 * the local copy is already the right one. Nothing is executed here — the tree it produces is only ever
 * an input to `docker build`.
 *
 * The result is a directory whose children are the payload paths as they appear in the archive
 * (`usr/local/bin/foo`), which is what lets the generated Dockerfile `COPY <digest>/ /` the whole tree
 * onto the container root.
 */
class CliPackageStore(
    private val minioClient: MinioClient,
    private val bucket: String,
    private val cacheDir: Path,
) {

    private val log = LoggerFactory.getLogger(CliPackageStore::class.java)

    // One JVM-wide download per digest: two sessions started at once should not fetch the same 40 MB twice
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * @return the directory holding this package's payload tree
     * @throws IllegalStateException when [packageDigest] is not a sha256 hex or [objectKey] is blank, when
     *                              the object is missing, or when it does not hash to [packageDigest]
     */
    fun materialize(
        packageDigest: String,
        objectKey: String,
    ): Path {
        // The digest is a path component below (cache dir, plus the `.complete` marker named after it),
        // and it arrives in the agent spec from admin rather than from anything this process built.
        // Shape is the only thing that can be checked before the download hashes the bytes.
        if (!CliPackageLayout.DIGEST_PATTERN.matches(packageDigest)) {
            throw IllegalStateException(
                "CLI package digest (${packageDigest.length} chars) is not a sha256 hex — " +
                    "this CLI was not registered from a package",
            )
        }
        if (objectKey.isBlank()) {
            throw IllegalStateException(
                "CLI package $packageDigest has no object key — it was not registered from a package",
            )
        }
        val tree = cacheDir.resolve(packageDigest)
        if (isComplete(packageDigest, tree)) return tree.used()

        synchronized(locks.computeIfAbsent(packageDigest) { Any() }) {
            if (isComplete(packageDigest, tree)) return tree.used()
            downloadAndExtract(packageDigest, objectKey, tree)
            return tree
        }
    }

    /**
     * Drops the payload trees this host no longer needs.
     *
     * The cache only ever grows: a package that gets a new version leaves its old digest behind, and one
     * that leaves admin's directory altogether takes its archive with it, so the tree can never be rebuilt
     * and is just disk. Both halves of the judgement matter. Unreferenced alone would delete a tree a
     * download or a `docker build` is reading right now, and old alone would delete a package that is still
     * registered — it would come back, but only after that session paid for a fresh download.
     *
     * Anything in the directory this class did not write is left alone.
     *
     * @param inUse the `packageDigest` of every package admin still registers
     * @param grace how recently a tree has to have been used to survive
     * @return how many payload trees were removed
     */
    fun evictUnused(
        inUse: Set<String>,
        grace: Duration,
    ): Int {
        if (!Files.isDirectory(cacheDir)) return 0
        val cutoff = Instant.now().minus(grace)
        val entries = Files.list(cacheDir).use { it.toList() }.map { it.fileName.toString() }.toSet()
        var removed = 0
        // Markers are scanned too: an orphan marker names a tree this host never finished publishing.
        val candidates = (
            entries.filter { CliPackageLayout.DIGEST_PATTERN.matches(it) } +
                entries.filter { it.endsWith(MARKER_SUFFIX) }.map { it.removeSuffix(MARKER_SUFFIX) }
            ).distinct()
        for (digest in candidates) {
            if (digest in inUse) continue
            val tree = cacheDir.resolve(digest)
            val marker = cacheDir.resolve("$digest$MARKER_SUFFIX")
            val present = listOf(tree, marker).filter { Files.exists(it) }
            if (present.isEmpty() || present.any { lastModified(it).isAfter(cutoff) }) continue
            try {
                tree.toFile().deleteRecursively()
                Files.deleteIfExists(marker)
                removed++
                log.info("[cliCache] Evicted payload tree {}", digest.take(12))
            } catch (e: Exception) {
                log.warn("[cliCache] Payload tree {} could not be removed: {}", digest.take(12), e.message)
            }
        }
        evictStaging(entries, cutoff)
        return removed
    }

    /**
     * Removes staging directories left by a run that was killed mid-download.
     *
     * A failed [downloadAndExtract] cleans its own staging in a `finally`; only a killed JVM leaves one
     * behind, and nothing else ever looks at it again. Fresh ones belong to a download in progress.
     */
    private fun evictStaging(
        entries: Set<String>,
        cutoff: Instant,
    ) {
        for (name in entries.filter { it.startsWith(STAGING_PREFIX) }) {
            val staging = cacheDir.resolve(name)
            try {
                if (lastModified(staging).isAfter(cutoff)) continue
                staging.toFile().deleteRecursively()
                log.info("[cliCache] Removed interrupted staging directory {}", name)
            } catch (e: Exception) {
                log.warn("[cliCache] Staging directory {} could not be removed: {}", name, e.message)
            }
        }
    }

    private fun lastModified(path: Path): Instant = try {
        Files.getLastModifiedTime(path).toInstant()
    } catch (e: Exception) {
        // An unreadable timestamp reads as "just used": the sweep runs again on the next start.
        Instant.now()
    }

    /** Reading the cache is a use, and eviction measures idleness from this moment. */
    private fun Path.used(): Path {
        try {
            Files.setLastModifiedTime(this, FileTime.from(Instant.now()))
        } catch (e: Exception) {
            log.debug("[cliCache] Could not record a use of {}: {}", this, e.message)
        }
        return this
    }

    private fun isComplete(
        packageDigest: String,
        tree: Path,
    ): Boolean = Files.isDirectory(tree) && Files.exists(cacheDir.resolve("$packageDigest$MARKER_SUFFIX"))

    private fun downloadAndExtract(
        packageDigest: String,
        objectKey: String,
        tree: Path,
    ) {
        Files.createDirectories(cacheDir)
        // Inside cacheDir, so the finished tree can be moved into place atomically — a temp dir on
        // another filesystem would turn that move into a copy and half-built trees into a real risk.
        val staging = Files.createTempDirectory(cacheDir, STAGING_PREFIX)
        try {
            val archive = staging.resolve("package.zip")
            val sha256 = MessageDigest.getInstance("SHA-256")
            minioClient.getObject(GetObjectArgs.builder().bucket(bucket).`object`(objectKey).build()).use { remote ->
                DigestInputStream(remote, sha256).use { digesting ->
                    Files.newOutputStream(archive).use { digesting.copyTo(it) }
                }
            }
            val actual = sha256.digest().joinToString("") { "%02x".format(it) }
            check(actual == packageDigest) {
                "CLI package object '$objectKey' hashes to $actual, expected $packageDigest — " +
                    "the stored archive is not what admin registered"
            }

            val payload = staging.resolve("payload")
            Files.createDirectories(payload)
            val archiveFile = archive.toFile()
            val written = CliPackageArchive.open(archiveFile).use {
                it.extractTree(CliPackageLayout.PAYLOAD_PREFIX, payload.toFile())
            }
            check(written > 0) { "CLI package $packageDigest carries no payload/ entries" }

            // A tree with no `.complete` marker is an interrupted earlier run, and rename refuses to
            // move onto a non-empty directory: clearing the residue is what lets this host recover.
            if (Files.exists(tree)) tree.toFile().deleteRecursively()
            try {
                Files.move(payload, tree, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(payload, tree)
            }
            Files.writeString(cacheDir.resolve("$packageDigest$MARKER_SUFFIX"), packageDigest)
            log.info("Materialized CLI package payload: digest={}, files={}, path={}", packageDigest.take(12), written, tree)
        } finally {
            // The tree appears through the atomic move or not at all, so a failed attempt leaves nothing
            // behind but this staging directory.
            staging.toFile().deleteRecursively()
        }
    }

    companion object {
        /** Written next to a finished tree; its absence is what makes a tree an interrupted run. */
        private const val MARKER_SUFFIX = ".complete"

        /** Prefix of the temporary directory a download unpacks into before the atomic move. */
        private const val STAGING_PREFIX = ".staging-"
    }
}
