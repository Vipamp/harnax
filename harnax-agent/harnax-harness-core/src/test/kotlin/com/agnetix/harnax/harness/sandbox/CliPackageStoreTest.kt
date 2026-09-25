package com.agnetix.harnax.harness.sandbox

import io.minio.GetObjectArgs
import io.minio.GetObjectResponse
import io.minio.MinioClient
import okhttp3.Headers
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Unit tests for [CliPackageStore]: the cache is keyed by the digest admin registered, and an archive
 * that does not hash to that digest must never become a tree `docker build` could consume.
 *
 * Packages here are real zips carrying the unix modes a packer would record. A payload binary that
 * loses its execute bit fails its check command with 126 inside the container, far from this code, so
 * the mode trip through extraction belongs to these tests.
 */
class CliPackageStoreTest {

    private val bucket = "harnax-cli-packages"
    private val objectKey = "demo-1.0.0.harnaxcli.zip"
    private val minioClient = mock<MinioClient>()

    @TempDir
    lateinit var cacheDir: Path

    private lateinit var store: CliPackageStore

    @BeforeEach
    fun setUp() {
        store = CliPackageStore(minioClient, bucket, cacheDir)
    }

    private fun serve(bytes: ByteArray) {
        whenever(minioClient.getObject(any<GetObjectArgs>())).thenAnswer {
            // A real response, not a mock: `readBytes` on a stub whose matcher misses loops on 0 forever.
            GetObjectResponse(Headers.Builder().build(), bucket, objectKey, null, ByteArrayInputStream(bytes))
        }
    }

    private fun packageBytes(vararg entries: Pair<String, Pair<String, Int>>): ByteArray {
        val zip = Files.createTempFile("harnax-cli-package", ".zip")
        ZipArchiveOutputStream(Files.newOutputStream(zip)).use { out ->
            for ((name, content) in entries) {
                val (text, mode) = content
                val bytes = text.toByteArray()
                out.putArchiveEntry(ZipArchiveEntry(name).apply { setUnixMode(mode) })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        return zip.toFile().also { it.deleteOnExit() }.readBytes()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun standardPackage(): ByteArray = packageBytes(
        "plugin.yaml" to ("name: demo\nversion: 1.0.0\n" to 0b110_100_100),
        "skill/SKILL.md" to ("# demo\n" to 0b110_100_100),
        "payload/usr/local/bin/demo" to ("#!/bin/sh\necho demo\n" to 0b111_101_101),
        "payload/usr/local/share/demo/help.txt" to ("usage\n" to 0b110_100_100),
    )

    private fun cacheFileNames(): List<String> = Files.list(cacheDir).map { cacheDir.relativize(it).toString() }.toList()

    @Test
    fun `payload lands at the paths the package declared`() {
        val archive = standardPackage()
        serve(archive)

        val tree = store.materialize(sha256(archive), objectKey)

        assertEquals("#!/bin/sh\necho demo\n", Files.readString(tree.resolve("usr/local/bin/demo")))
        assertEquals("usage\n", Files.readString(tree.resolve("usr/local/share/demo/help.txt")))
        // Only the payload: the manifest and the skill are admin's business, not image content.
        assertFalse(Files.exists(tree.resolve("plugin.yaml")))
        assertFalse(Files.exists(tree.resolve("skill")))
    }

    /** The packed execute bit is what makes the check command runnable at all. */
    @Test
    fun `payload binary keeps its packed mode`() {
        val archive = standardPackage()
        serve(archive)

        val binary = store.materialize(sha256(archive), objectKey).resolve("usr/local/bin/demo")

        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(binary)))
    }

    /**
     * `zip -r` records every intermediate directory as an entry of its own, named with a trailing slash.
     * Reading those names through the payload path rule unchanged made the slash an empty segment and
     * refused the whole package, so a real packer's output could register but never install.
     */
    @Test
    fun `a package carrying explicit directory entries still installs`() {
        val archive = packageBytes(
            "plugin.yaml" to ("name: demo\nversion: 1.0.0\n" to 0b110_100_100),
            "skill/SKILL.md" to ("# demo\n" to 0b110_100_100),
            "payload/" to ("" to 0b0100_111_101_101),
            "payload/usr/" to ("" to 0b0100_111_101_101),
            "payload/usr/local/" to ("" to 0b0100_111_101_101),
            "payload/usr/local/bin/" to ("" to 0b0100_111_101_101),
            "payload/usr/local/bin/demo" to ("#!/bin/sh\necho demo\n" to 0b111_101_101),
        )
        serve(archive)

        val tree = store.materialize(sha256(archive), objectKey)

        assertEquals("#!/bin/sh\necho demo\n", Files.readString(tree.resolve("usr/local/bin/demo")))
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(tree.resolve("usr/local/bin/demo"))))
    }

    /** Dropping the directory marker off the name before the check must not drop the check itself. */
    @Test
    fun `a directory entry outside the payload root is still refused`() {
        val archive = packageBytes(
            "plugin.yaml" to ("name: demo\nversion: 1.0.0\n" to 0b110_100_100),
            "payload/" to ("" to 0b0100_111_101_101),
            "payload/usr/local/bin/demo" to ("#!/bin/sh\n" to 0b111_101_101),
            "payload/../escape/" to ("" to 0b0100_111_101_101),
        )
        val digest = sha256(archive)
        serve(archive)

        val ex = assertThrows(IllegalArgumentException::class.java) { store.materialize(digest, objectKey) }

        assertTrue(ex.message!!.contains("escape"), ex.message!!)
        assertFalse(Files.exists(cacheDir.resolve(digest)), "a refused package must not publish a tree")
        assertTrue(cacheFileNames().none { it.startsWith(".staging-") }, "staging left behind: ${cacheFileNames()}")
    }

    @Test
    fun `an archive that does not hash to the registered digest is refused`() {
        serve(standardPackage())
        val otherDigest = sha256("different bytes".toByteArray())

        val ex = assertThrows(IllegalStateException::class.java) { store.materialize(otherDigest, objectKey) }

        assertTrue(ex.message!!.contains(otherDigest.take(12)))
        assertFalse(Files.exists(cacheDir.resolve(otherDigest)), "a refused package must not publish a tree")
        assertFalse(Files.exists(cacheDir.resolve("$otherDigest.complete")))
        assertTrue(cacheFileNames().none { it.startsWith(".staging-") }, "staging left behind: ${cacheFileNames()}")
    }

    @Test
    fun `the cache answers a second request without another download`() {
        val archive = standardPackage()
        val digest = sha256(archive)
        serve(archive)

        val first = store.materialize(digest, objectKey)
        val second = store.materialize(digest, objectKey)

        assertEquals(first, second)
        verify(minioClient, times(1)).getObject(any<GetObjectArgs>())
    }

    /**
     * A tree whose marker is missing is the residue of an interrupted run, and `rename` refuses to move
     * onto a non-empty directory — without clearing it, this host would fail on that digest forever.
     */
    @Test
    fun `a tree left without its marker is rebuilt`() {
        val archive = standardPackage()
        val digest = sha256(archive)
        val stale = Files.createDirectories(cacheDir.resolve(digest).resolve("usr/local/bin")).resolve("stale")
        Files.writeString(stale, "from an interrupted run")
        serve(archive)

        val tree = store.materialize(digest, objectKey)

        assertFalse(Files.exists(tree.resolve("usr/local/bin/stale")), "the leftover tree must be replaced, not merged")
        assertTrue(Files.exists(cacheDir.resolve("$digest.complete")))
        assertEquals(setOf(digest, "$digest.complete"), cacheFileNames().toSet())
    }

    @Test
    fun `a package carrying no payload is refused`() {
        val archive = packageBytes(
            "plugin.yaml" to ("name: demo\n" to 0b110_100_100),
            "skill/SKILL.md" to ("# demo\n" to 0b110_100_100),
        )
        serve(archive)

        val ex = assertThrows(IllegalStateException::class.java) { store.materialize(sha256(archive), objectKey) }

        assertTrue(ex.message!!.contains("no payload"))
        assertTrue(cacheFileNames().isEmpty())
    }

    /**
     * `checkPayloadPath` runs again on the way out, so an archive that does not match what was
     * registered cannot put a file anywhere the image would then carry.
     */
    @Test
    fun `a payload path that escapes the container root never reaches the cache`() {
        val archive = packageBytes(
            "plugin.yaml" to ("name: demo\n" to 0b110_100_100),
            "payload/usr/local/bin/demo" to ("ok\n" to 0b111_101_101),
            "payload/etc/cron.d/demo" to ("bad\n" to 0b110_100_100),
        )
        val digest = sha256(archive)
        serve(archive)

        assertThrows(IllegalArgumentException::class.java) { store.materialize(digest, objectKey) }

        assertFalse(Files.exists(cacheDir.resolve(digest)))
        assertFalse(Files.exists(cacheDir.resolve("$digest.complete")))
        assertTrue(cacheFileNames().none { it.startsWith(".staging-") }, "staging left behind: ${cacheFileNames()}")
    }

    @Test
    fun `a digest that is not a sha256 hex fails before it becomes a path`() {
        val traversal = "../../etc/passwd"
        val sha = sha256("x".toByteArray())
        val cases = listOf("", traversal, sha.drop(1))
        for (digest in cases) {
            val ex = assertThrows(IllegalStateException::class.java) { store.materialize(digest, objectKey) }
            assertFalse(ex.message!!.contains(traversal), "refusal echoed the caller's value: ${ex.message}")
        }
        assertThrows(IllegalStateException::class.java) { store.materialize(sha, "") }

        verify(minioClient, never()).getObject(any<GetObjectArgs>())
        assertTrue(cacheFileNames().isEmpty())
    }

    /**
     * CLI-04's cache half: the trees are rebuildable, so what makes removal safe is not age alone but
     * "admin no longer registers this digest" *and* "no build asked for it recently". The second half is
     * what protects a download or a `docker build` that is still running while this sweep happens.
     */
    @Nested
    inner class Eviction {

        private fun treeOf(
            digest: String,
            age: Duration,
        ) {
            val tree = Files.createDirectories(cacheDir.resolve(digest))
            Files.writeString(tree.resolve("bin"), "payload")
            val marker = cacheDir.resolve("$digest.complete")
            Files.writeString(marker, digest)
            backdate(tree, age)
            backdate(marker, age)
        }

        private fun backdate(
            path: Path,
            age: Duration,
        ) = Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age)))

        @Test
        fun `a tree no registered package names is removed with its marker`() {
            val gone = sha256("gone".toByteArray())
            treeOf(gone, Duration.ofDays(2))

            assertEquals(1, store.evictUnused(emptySet(), Duration.ofHours(6)))

            assertFalse(Files.exists(cacheDir.resolve(gone)))
            assertFalse(Files.exists(cacheDir.resolve("$gone.complete")))
        }

        /** A pruned package cannot be re-fetched any more, so a live agent is the only thing that matters. */
        @Test
        fun `a tree admin still registers is kept however old it is`() {
            val live = sha256("live".toByteArray())
            treeOf(live, Duration.ofDays(30))

            assertEquals(0, store.evictUnused(setOf(live), Duration.ofHours(6)))

            assertTrue(Files.exists(cacheDir.resolve(live)))
            assertTrue(Files.exists(cacheDir.resolve("$live.complete")))
        }

        @Test
        fun `an unreferenced tree inside the grace window is kept`() {
            val inFlight = sha256("in-flight".toByteArray())
            treeOf(inFlight, Duration.ofMinutes(5))

            assertEquals(0, store.evictUnused(emptySet(), Duration.ofHours(6)))

            assertTrue(Files.exists(cacheDir.resolve(inFlight)))
        }

        /**
         * Reading the cache is a use: without the touch, a package an agent runs every day would age out
         * on the first quiet week and that day's session would pay for a re-download.
         */
        @Test
        fun `a cache hit counts as a use`() {
            val archive = standardPackage()
            val digest = sha256(archive)
            serve(archive)
            store.materialize(digest, objectKey)
            backdate(cacheDir.resolve(digest), Duration.ofDays(2))
            backdate(cacheDir.resolve("$digest.complete"), Duration.ofDays(2))

            store.materialize(digest, objectKey)

            assertEquals(0, store.evictUnused(emptySet(), Duration.ofHours(6)))
            assertTrue(Files.exists(cacheDir.resolve(digest)))
        }

        @Test
        fun `staging left by an interrupted run is removed once it is old`() {
            val staleStaging = Files.createDirectories(cacheDir.resolve(".staging-killed"))
            Files.writeString(staleStaging.resolve("package.zip"), "half a download")
            Files.setLastModifiedTime(staleStaging, FileTime.from(Instant.now().minus(Duration.ofDays(2))))
            val freshStaging = Files.createDirectories(cacheDir.resolve(".staging-running"))
            Files.writeString(freshStaging.resolve("package.zip"), "downloading")

            store.evictUnused(emptySet(), Duration.ofHours(6))

            assertFalse(Files.exists(staleStaging))
            assertTrue(Files.exists(freshStaging), "a staging dir this JVM is filling must survive")
        }

        /**
         * The cache dir is operator-visible, so anything not shaped like this class's own output is left
         * alone rather than deleted on a guess about what put it there.
         */
        @Test
        fun `anything this class did not write is left alone`() {
            Files.writeString(cacheDir.resolve("notes.txt"), "an operator's file")
            Files.createDirectories(cacheDir.resolve("not-a-digest"))

            assertEquals(0, store.evictUnused(emptySet(), Duration.ofHours(6)))

            assertTrue(Files.exists(cacheDir.resolve("notes.txt")))
            assertTrue(Files.exists(cacheDir.resolve("not-a-digest")))
        }

        @Test
        fun `an orphan marker is removed with nothing left to rebuild`() {
            val digest = sha256("orphan marker".toByteArray())
            Files.writeString(cacheDir.resolve("$digest.complete"), digest)
            Files.setLastModifiedTime(cacheDir.resolve("$digest.complete"), FileTime.from(Instant.now().minus(Duration.ofDays(2))))

            assertEquals(1, store.evictUnused(emptySet(), Duration.ofHours(6)))

            assertFalse(Files.exists(cacheDir.resolve("$digest.complete")))
        }
    }
}
