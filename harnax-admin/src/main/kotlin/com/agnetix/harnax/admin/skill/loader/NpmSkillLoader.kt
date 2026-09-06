package com.agnetix.harnax.admin.skill.loader

import io.agentscope.core.skill.AgentSkill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

@Component
class NpmSkillLoader : SkillLoader {
    override val sourceType = "NPM"

    companion object {
        /**
         * npm package name rules, tightened so the value can never be mistaken for a CLI flag
         * (no leading `-`) nor escape the install directory (no `..` segment, checked separately).
         */
        private val PACKAGE_NAME_REGEX = Regex("^(?:@[a-z0-9~][a-z0-9._~-]*/)?[a-z0-9~][a-z0-9._~-]*$")
        private const val MAX_PACKAGE_NAME_LENGTH = 214
        private val REGISTRY_REGEX = Regex("^https?://[^\\s]+$")
        private const val INSTALL_TIMEOUT_SECONDS = 120L

        /**
         * How much of the npm transcript is quoted back to the caller.
         *
         * The failure reason sits at the end of the log, so the tail is what matters; the head is
         * registry progress and deprecation warnings. Kept below the 500-character ceiling
         * `ApiErrors` applies so the tail survives intact instead of being cut from the front.
         */
        private const val MAX_REPORTED_OUTPUT = 400

        /** Grace period for the killed process tree to actually exit before the temp dir goes. */
        private const val DESTROY_GRACE_SECONDS = 5L
    }

    private val log = LoggerFactory.getLogger(NpmSkillLoader::class.java)

    override fun loadSkills(config: Map<String, Any>, tmpDir: Path): SkillLoadResult {
        val packageName = config["packageName"] as? String
            ?: throw IllegalArgumentException("NPM source config requires 'packageName'")
        val registry = config["registry"] as? String
        validateConfig(config)

        val installDir = Files.createTempDirectory(tmpDir, "npm-skill-")

        val command = mutableListOf(
            "npm",
            "install",
            packageName,
            "--prefix",
            installDir.toString(),
            // Lifecycle scripts of an arbitrary package would run inside the admin JVM's
            // own process context — a skill import must never execute downloaded code.
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
        )
        if (!registry.isNullOrBlank()) {
            command.addAll(listOf("--registry", registry))
        }

        log.info("Installing npm skill package: {} in {}", packageName, installDir)
        // Redirect output to a file: reading stdout inline would block until process
        // exit and defeat the waitFor timeout below
        val outputFile = Files.createTempFile(installDir, "npm-output-", ".log")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start()

        val finished = process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!finished) {
            // npm spawns children of its own; killing only the direct child left them running and
            // still writing into the temp directory the caller is about to delete
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)
            log.error("npm install timed out for package {}, output so far: {}", packageName, readOutput(outputFile))
            throw RuntimeException("npm install timed out after ${INSTALL_TIMEOUT_SECONDS}s for package: $packageName")
        }
        if (process.exitValue() != 0) {
            val output = readOutput(outputFile)
            log.error("npm install failed for package {}: {}", packageName, output)
            throw RuntimeException("npm install failed for package $packageName: ${tailOf(output)}")
        }

        val pkgDir = installDir.resolve("node_modules/$packageName").normalize()
        // Defence in depth: the name is already validated, but never resolve outside the sandbox
        if (!pkgDir.startsWith(installDir)) {
            throw SecurityException("Resolved package directory escapes the install dir: $pkgDir")
        }
        if (!pkgDir.isDirectory()) {
            throw RuntimeException("Package directory not found: $pkgDir")
        }

        return parseSkillDirectories(pkgDir)
    }

    private fun readOutput(outputFile: Path): String = try {
        Files.readString(outputFile)
    } catch (e: Exception) {
        log.warn("Could not read the npm output file {}: {}", outputFile, e.message)
        ""
    }

    /** Last [MAX_REPORTED_OUTPUT] characters, which is where npm puts the actual error. */
    private fun tailOf(output: String): String {
        val trimmed = output.trim()
        return when {
            trimmed.isEmpty() -> "no output captured"
            trimmed.length <= MAX_REPORTED_OUTPUT -> trimmed
            else -> "..." + trimmed.takeLast(MAX_REPORTED_OUTPUT)
        }
    }

    override fun validateConfig(config: Map<String, Any>) {
        val packageName = config["packageName"] as? String
        if (packageName.isNullOrBlank()) {
            throw IllegalArgumentException("NPM source config requires 'packageName'")
        }
        if (packageName.length > MAX_PACKAGE_NAME_LENGTH || !PACKAGE_NAME_REGEX.matches(packageName)) {
            throw IllegalArgumentException(
                "Invalid NPM package name '$packageName'. Expected an npm-compliant name such as " +
                    "'my-skills' or '@scope/my-skills' (lowercase letters, digits and . _ ~ - only).",
            )
        }
        if (packageName.contains("..")) {
            throw IllegalArgumentException("NPM package name must not contain '..': $packageName")
        }
        val registry = config["registry"] as? String
        if (!registry.isNullOrBlank() && !REGISTRY_REGEX.matches(registry)) {
            throw IllegalArgumentException("Invalid NPM registry '$registry'. Expected an http(s) URL.")
        }
    }

    private fun parseSkillDirectories(pkgDir: Path): SkillLoadResult {
        val skills = mutableListOf<AgentSkill>()
        val failures = mutableListOf<SkillLoadFailure>()
        val skipped = mutableListOf<String>()

        pkgDir.listDirectoryEntries().sorted().forEach { entry ->
            if (entry.isDirectory()) {
                val skill = buildSkill(entry, entry.resolve("SKILL.md"), entry.name, failures)
                if (skill != null) {
                    skills.add(skill)
                } else {
                    skipped.add(entry.name)
                }
            }
        }

        // Tried even when a subdirectory failed: the package may ship one broken skill next to a
        // perfectly readable top-level one, and refusing the good half helps nobody
        if (skills.isEmpty()) {
            buildSkill(pkgDir, pkgDir.resolve("SKILL.md"), pkgDir.name, failures)?.let { skills.add(it) }
        }

        // A successful install that exposes nothing is the hardest case to diagnose: the caller only
        // sees "the source yielded no skills", so name the directories that were passed over. The
        // unreadable ones are reported to the operator through `failures` and counted here for the log
        if (skills.isEmpty()) {
            log.warn(
                "npm package under {} installed successfully but exposes no usable SKILL.md; unreadable: {}, skipped directories: {}",
                pkgDir,
                failures.size,
                if (skipped.isEmpty()) "(none)" else skipped.joinToString(", "),
            )
        }

        return SkillLoadResult(skills, failures)
    }

    /**
     * Reads one skill directory, or returns null when there is nothing usable to read.
     *
     * A directory with no `SKILL.md` stays silent: an npm package routinely holds directories that
     * were never meant to be skills. A `SKILL.md` that is there but cannot be used is recorded in
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
