package com.agnetix.harnax.common.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipFile

/**
 * One entry of a CLI package archive.
 *
 * [mode] carries the unix `st_mode` recorded in the zip central directory's external attributes,
 * exactly as the packer saw them on the source tree. A payload binary that was not executable when
 * it was packed therefore arrives non-executable, and nothing downstream has to guess: that is why
 * the mode travels with the entry rather than being assumed to be 0755.
 */
data class CliPackageEntry(
    val archivePath: String,
    val mode: Int,
    val directory: Boolean,
    val size: Long,
) {
    /** Any of the three execute bits — the payload check command fails with 126 without them. */
    val isExecutable: Boolean
        get() = mode and EXEC_BITS != 0

    /**
     * The packed entry was a symbolic link on the author's machine. Extraction writes content, never
     * links, so such an entry would silently become a file holding a path — rejected at registration.
     */
    val isSymbolicLink: Boolean
        get() = mode and S_IFMT == S_IFLNK

    /** setuid / setgid / sticky: a packaged binary must not arrive with elevated-behaving bits. */
    val hasSpecialPermissionBits: Boolean
        get() = mode and SPECIAL_BITS != 0

    companion object {
        private const val EXEC_BITS = 0b001_001_001
        private const val SPECIAL_BITS = 0b110_000_000_000
        private const val S_IFMT = 0b1111_0000_0000_0000
        private const val S_IFLNK = 0b1010_0000_0000_0000
    }
}

/**
 * Read-only view over a `.harnaxcli.zip` package.
 *
 * [java.util.zip.ZipFile] answers names and content but exposes no accessor for the central
 * directory's external attributes, so the modes come from a direct walk of that directory instead.
 * Inventing a uniform 0644 would turn every packaged binary into "permission denied", surfacing far
 * from its cause as a failed image check command, so the two views are cross-checked and a package
 * they disagree about is rejected rather than half-trusted.
 *
 * Nothing here executes or installs anything; the class only reports what the archive holds
 * (design invariant I4).
 */
class CliPackageArchive private constructor(
    file: File,
    modes: Map<String, Int>,
) : AutoCloseable {
    private val zipFile = ZipFile(file)

    val entries: List<CliPackageEntry> = zipFile.entries().toList().map { entry ->
        CliPackageEntry(
            archivePath = entry.name,
            mode = modes[entry.name] ?: if (entry.isDirectory) DEFAULT_DIRECTORY_MODE else DEFAULT_FILE_MODE,
            directory = entry.isDirectory,
            size = entry.size.coerceAtLeast(0L),
        )
    }

    /**
     * File entries the archive carried no unix mode for, so [DEFAULT_FILE_MODE] stood in for them.
     *
     * A package zipped on Windows is the normal way to end up here, and its binaries then land
     * non-executable. Registration surfaces this rather than letting the image build discover it
     * through a failed check command.
     */
    val entriesWithoutStoredMode: List<String> = entries
        .filterNot { it.directory }
        .filterNot { modes.containsKey(it.archivePath) }
        .map { it.archivePath }

    /**
     * Entry names the archive carries more than once.
     *
     * A zip is allowed to repeat a name, and then the readers disagree about what the package holds:
     * [readBytes] answers with the first match, [extractTree] writes in listing order so the last one
     * survives on disk, and the digests hash every occurrence. A package like this means something
     * different to each of them, which is the one thing a content-addressed artefact may not do.
     */
    val duplicateEntryNames: List<String> = entries
        .groupingBy { it.archivePath }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()

    fun has(archivePath: String): Boolean = zipFile.getEntry(archivePath) != null

    fun readBytes(archivePath: String): ByteArray {
        val entry = zipFile.getEntry(archivePath)
            ?: throw IllegalArgumentException("No such package entry: $archivePath")
        return zipFile.getInputStream(entry).use { it.readBytes() }
    }

    /**
     * Read an entry that must stay under [maxBytes], returning null as soon as it goes over.
     *
     * The size in the central directory is whatever the packer claimed, and `-1` for a streamed entry,
     * so a cap checked against `readBytes(...).size` can only be enforced after the whole entry is in
     * memory. The text resources a package ships — manifest, skill, skill assets — are all documents a
     * human wrote, so this stops reading at the limit rather than measuring the overage.
     */
    fun readBounded(
        archivePath: String,
        maxBytes: Int,
    ): ByteArray? {
        val entry = zipFile.getEntry(archivePath)
            ?: throw IllegalArgumentException("No such package entry: $archivePath")
        zipFile.getInputStream(entry).use { input ->
            val kept = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_CHUNK_BYTES))
            val chunk = ByteArray(DEFAULT_CHUNK_BYTES)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read < 0) return kept.toByteArray()
                total += read
                if (total > maxBytes) return null
                kept.write(chunk, 0, read)
            }
        }
    }

    fun entriesUnder(prefix: String): List<CliPackageEntry> = entries.filter { it.archivePath.startsWith(prefix) }

    fun totalSizeUnder(prefix: String): Long = entriesUnder(prefix).filterNot { it.directory }.sumOf { it.size }

    fun fileCountUnder(prefix: String): Int = entriesUnder(prefix).count { !it.directory }

    /**
     * Extract every file under [prefix] into [targetRoot], each to the path it has in the archive
     * minus the prefix, and restore its packed mode. Returns the number of files written.
     *
     * The destination is re-checked on the way out — both against the denied container targets and
     * against [targetRoot] itself. Registration already validated the paths, but extraction runs
     * against a digest-keyed directory handed to `docker build`, and an archive that does not match
     * what was registered is exactly the case worth refusing here.
     */
    fun extractTree(
        prefix: String,
        targetRoot: File,
    ): Int {
        var written = 0
        for (entry in entriesUnder(prefix)) {
            // A directory entry is a file path plus the trailing slash that makes it a directory; the
            // registration side only checks non-directory entries, so strip it to keep one rule.
            val relative = entry.archivePath.removePrefix(prefix).removeSuffix("/")
            if (relative.isBlank()) continue
            CliPackageLayout.checkPayloadPath(relative)?.let { reason ->
                throw IllegalArgumentException("$reason — refusing to extract ${entry.archivePath}")
            }
            val target = CliPackageLayout.resolveInside(targetRoot, relative)
            if (entry.directory) {
                target.mkdirs()
                continue
            }
            target.parentFile?.mkdirs()
            zipFile.getInputStream(zipFile.getEntry(entry.archivePath)).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            applyMode(target, entry.mode)
            written++
        }
        return written
    }

    override fun close() {
        zipFile.close()
    }

    companion object {
        /** 0644 regular file: what a non-UNIX packer means, type bits included. */
        const val DEFAULT_FILE_MODE = 0b1000_0000_0000_0000 or 0b110_100_100
        const val DEFAULT_DIRECTORY_MODE = 0b0100_0000_0000_0000 or 0b111_101_101

        /** Size of [readBounded]'s pump; also the cap on its initial buffer, which a small limit should not blow. */
        private const val DEFAULT_CHUNK_BYTES = 8192

        fun open(file: File): CliPackageArchive = CliPackageArchive(file, ZipCentralDirectoryModes.read(file))

        /**
         * Best-effort mode restore. Filesystems without a posix view (a Windows dev box) keep their
         * own inheritance rules instead of failing the whole extraction — the image build that
         * consumes the tree happens on Linux, where the bits do get written.
         */
        internal fun applyMode(
            file: File,
            mode: Int,
        ) {
            val path = file.toPath()
            if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(rwxString(mode)))
        }

        internal fun rwxString(mode: Int): String = buildString {
            for (shift in 6 downTo 0 step 3) {
                val bits = (mode shr shift) and 0b111
                append(if (bits and 0b100 != 0) 'r' else '-')
                append(if (bits and 0b010 != 0) 'w' else '-')
                append(if (bits and 0b001 != 0) 'x' else '-')
            }
        }
    }
}
