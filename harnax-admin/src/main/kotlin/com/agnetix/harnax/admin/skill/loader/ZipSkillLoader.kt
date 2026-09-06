package com.agnetix.harnax.admin.skill.loader

import io.agentscope.core.skill.AgentSkill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.*

@Component
class ZipSkillLoader : SkillLoader {
    override val sourceType = "ZIP"

    companion object {
        /** Zip-bomb guard rails: a skill archive is a handful of Markdown files, never gigabytes. */
        private const val MAX_ENTRIES = 5_000
        private const val MAX_ENTRY_BYTES = 20L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
        private const val MAX_ENTRY_NAME_LENGTH = 512
    }

    private val log = LoggerFactory.getLogger(ZipSkillLoader::class.java)

    override fun loadSkills(config: Map<String, Any>, tmpDir: Path): SkillLoadResult {
        val zipPath = config["zipPath"] as? String
            ?: throw IllegalArgumentException("ZIP source config requires 'zipPath'")

        val extractDir = Files.createTempDirectory(tmpDir, "zip-skill-")
        val zipFile = Path.of(zipPath)

        if (!zipFile.exists()) {
            throw IllegalArgumentException(
                "ZIP archive is no longer available. ZIP sources are installed once at upload time; " +
                    "upload the archive again to refresh their skills.",
            )
        }

        extractZip(zipFile, extractDir)
        return parseSkillDirectories(extractDir)
    }

    override fun validateConfig(config: Map<String, Any>) {
        if ((config["zipPath"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("ZIP source config requires 'zipPath'")
        }
    }

    private fun extractZip(zipFile: Path, extractDir: Path) {
        var entryCount = 0
        var totalBytes = 0L

        ZipFile(zipFile.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (++entryCount > MAX_ENTRIES) {
                    throw SecurityException("ZIP archive has too many entries (limit $MAX_ENTRIES)")
                }
                if (entry.name.length > MAX_ENTRY_NAME_LENGTH) {
                    throw SecurityException("ZIP entry name too long: ${entry.name.take(64)}...")
                }

                val target = extractDir.resolve(entry.name).normalize()

                if (!target.startsWith(extractDir)) {
                    throw SecurityException("ZIP entry outside extract directory: ${entry.name}")
                }

                if (entry.isDirectory) {
                    Files.createDirectories(target)
                    return@forEach
                }

                // The declared size is attacker-controlled and absent (`-1`) for entries written with
                // a data descriptor, so this only fast-fails an honest archive; [copyBounded] is the
                // check that actually holds
                if (entry.size > MAX_ENTRY_BYTES || totalBytes + entry.size > MAX_TOTAL_BYTES) {
                    throw SecurityException("ZIP archive exceeds the size limit (entry ${entry.name})")
                }

                Files.createDirectories(target.parent)
                val written = zip.getInputStream(entry).use { input ->
                    copyBounded(input, target, MAX_ENTRY_BYTES)
                }
                totalBytes += written
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw SecurityException("ZIP archive expands beyond $MAX_TOTAL_BYTES bytes")
                }
            }
        }
    }

    /**
     * Streams one entry to disk, refusing to write past [limit].
     *
     * `entry.size` is whatever the archive declares and is `-1` for entries written with a data
     * descriptor, so the pre-check above cannot bound what actually lands on disk. Counting while
     * copying stops an entry that declares nothing from filling the volume before it is noticed.
     */
    private fun copyBounded(input: java.io.InputStream, target: Path, limit: Long): Long {
        var total = 0L
        Files.newOutputStream(target).use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) {
                    throw SecurityException("ZIP entry '${target.name}' expands beyond $limit bytes")
                }
                out.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun parseSkillDirectories(extractDir: Path): SkillLoadResult {
        val skills = mutableListOf<AgentSkill>()
        val failures = mutableListOf<SkillLoadFailure>()
        val skipped = mutableListOf<String>()
        val rootDirs = extractDir.listDirectoryEntries().filter { it.isDirectory() }

        val searchRoot = if (rootDirs.size == 1) rootDirs.first() else extractDir

        searchRoot.listDirectoryEntries().sorted().forEach { entry ->
            if (entry.isDirectory()) {
                val skill = buildSkill(entry, entry.resolve("SKILL.md"), entry.name, failures)
                if (skill != null) {
                    skills.add(skill)
                } else {
                    skipped.add(entry.name)
                }
            }
        }

        // Tried even when a subdirectory failed: an archive may hold one broken skill next to a
        // perfectly readable top-level one, and refusing the good half helps nobody
        if (skills.isEmpty()) {
            buildSkill(searchRoot, searchRoot.resolve("SKILL.md"), searchRoot.name, failures)?.let { skills.add(it) }
        }

        // An archive that unpacks cleanly but holds no SKILL.md is the hardest case to diagnose: the
        // caller only sees "the source yielded no skills", so name what was passed over and where.
        // The unreadable ones reach the operator through `failures` and are counted here for the log
        if (skills.isEmpty()) {
            log.warn(
                "ZIP extracted to {} exposes no usable SKILL.md (searched {}); unreadable: {}, skipped directories: {}",
                extractDir,
                searchRoot,
                failures.size,
                if (skipped.isEmpty()) "(none)" else skipped.joinToString(", "),
            )
        }

        return SkillLoadResult(skills, failures)
    }

    /**
     * Reads one skill directory, or returns null when there is nothing usable to read.
     *
     * A directory with no `SKILL.md` stays silent: an archive routinely holds directories that were
     * never meant to be skills. A `SKILL.md` that is there but cannot be used is recorded in
     * [failures] instead — dropping it here is what made a broken skill disappear from the import
     * while the request still answered 200 with a smaller count.
     */
    private fun buildSkill(
        skillDir: Path,
        skillMd: Path,
        fallbackName: String,
        failures: MutableList<SkillLoadFailure>,
    ): AgentSkill? {
        if (!Files.exists(skillMd)) return null
        return try {
            val content = Files.readString(skillMd)
            if (content.isBlank()) {
                log.warn("Skipping skill {} with empty SKILL.md", fallbackName)
                failures.add(SkillLoadFailure(fallbackName, "SKILL.md is empty"))
                return null
            }
            val meta = SkillFileParser.parseMeta(content, fallbackName)
            val resources = SkillFileParser.loadResources(skillDir.resolve("resources"))

            val builder = AgentSkill.builder()
                .name(meta.name)
                .skillContent(content)
                .description(meta.description)
            if (resources.isNotEmpty()) {
                builder.resources(resources)
            }
            builder.build()
        } catch (e: Exception) {
            val reason = SkillFileParser.failureReason(e)
            log.warn("Failed to parse skill from {}: {}", skillDir, reason)
            failures.add(SkillLoadFailure(fallbackName, "SKILL.md could not be parsed: $reason"))
            null
        }
    }
}
