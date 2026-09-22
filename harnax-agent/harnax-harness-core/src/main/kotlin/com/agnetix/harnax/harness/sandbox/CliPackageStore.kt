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
import java.security.DigestInputStream
import java.security.MessageDigest

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
        if (isComplete(packageDigest, tree)) return tree

        synchronized(locks.computeIfAbsent(packageDigest) { Any() }) {
            if (isComplete(packageDigest, tree)) return tree
            downloadAndExtract(packageDigest, objectKey, tree)
            return tree
        }
    }

    private fun isComplete(
        packageDigest: String,
        tree: Path,
    ): Boolean = Files.isDirectory(tree) && Files.exists(cacheDir.resolve("$packageDigest.complete"))

    private fun downloadAndExtract(
        packageDigest: String,
        objectKey: String,
        tree: Path,
    ) {
        Files.createDirectories(cacheDir)
        // Inside cacheDir, so the finished tree can be moved into place atomically — a temp dir on
        // another filesystem would turn that move into a copy and half-built trees into a real risk.
        val staging = Files.createTempDirectory(cacheDir, ".staging-")
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
            Files.writeString(cacheDir.resolve("$packageDigest.complete"), packageDigest)
            log.info("Materialized CLI package payload: digest={}, files={}, path={}", packageDigest.take(12), written, tree)
        } finally {
            // The tree appears through the atomic move or not at all, so a failed attempt leaves nothing
            // behind but this staging directory.
            staging.toFile().deleteRecursively()
        }
    }
}
