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
        private val PACKAGE_NAME_REGEX = Regex("^[a-z0-9@/._-]+$")
    }

    private val log = LoggerFactory.getLogger(NpmSkillLoader::class.java)

    override fun loadSkills(config: Map<String, Any>, tmpDir: Path): List<AgentSkill> {
        val packageName = config["packageName"] as? String
            ?: throw IllegalArgumentException("NPM source config requires 'packageName'")
        val registry = config["registry"] as? String

        val installDir = Files.createTempDirectory(tmpDir, "npm-skill-")

        val command = mutableListOf("npm", "install", packageName, "--prefix", installDir.toString())
        if (!registry.isNullOrBlank()) {
            command.addAll(listOf("--registry", registry))
        }

        log.info("Installing npm skill package: {} in {}", packageName, installDir)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(120, TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("npm install timed out after 120s for package: $packageName")
        }
        if (process.exitValue() != 0) {
            log.error("npm install failed for package {}: {}", packageName, output)
            throw RuntimeException("npm install failed for package $packageName: $output")
        }

        val pkgDir = installDir.resolve("node_modules/$packageName")
        if (!pkgDir.isDirectory()) {
            throw RuntimeException("Package directory not found: $pkgDir")
        }

        return parseSkillDirectories(pkgDir)
    }

    override fun validateConfig(config: Map<String, Any>) {
        val packageName = config["packageName"] as? String
        if (packageName.isNullOrBlank()) {
            throw IllegalArgumentException("NPM source config requires 'packageName'")
        }
        if (!PACKAGE_NAME_REGEX.matches(packageName)) {
            throw IllegalArgumentException(
                "Invalid NPM package name '$packageName'. Only lowercase letters, digits, @, /, ., _, - are allowed.",
            )
        }
    }

    private fun parseSkillDirectories(pkgDir: Path): List<AgentSkill> {
        val skills = mutableListOf<AgentSkill>()

        pkgDir.listDirectoryEntries().forEach { entry ->
            if (entry.isDirectory()) {
                val skillMd = entry.resolve("SKILL.md")
                if (Files.exists(skillMd)) {
                    try {
                        val content = Files.readString(skillMd)
                        val description = extractDescription(content)
                        val resources = loadResources(entry.resolve("resources"))

                        val builder = AgentSkill.builder()
                            .name(entry.name)
                            .skillContent(content)
                            .description(description)
                        if (resources.isNotEmpty()) {
                            builder.resources(resources)
                        }
                        skills.add(builder.build())
                    } catch (e: Exception) {
                        log.warn("Failed to parse skill from {}: {}", entry, e.message)
                    }
                }
            }
        }

        if (skills.isEmpty()) {
            val topLevelMd = pkgDir.resolve("SKILL.md")
            if (Files.exists(topLevelMd)) {
                val content = Files.readString(topLevelMd)
                val description = extractDescription(content)
                val resources = loadResources(pkgDir.resolve("resources"))

                val builder = AgentSkill.builder()
                    .name(pkgDir.name)
                    .skillContent(content)
                    .description(description)
                if (resources.isNotEmpty()) {
                    builder.resources(resources)
                }
                skills.add(builder.build())
            }
        }

        return skills
    }

    private fun extractDescription(skillmd: String): String {
        val lines = skillmd.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue
            if (trimmed.isNotEmpty()) return trimmed.take(500)
        }
        return ""
    }

    private fun loadResources(resourcesDir: Path): Map<String, String> {
        if (!resourcesDir.isDirectory()) return emptyMap()
        val resources = mutableMapOf<String, String>()
        resourcesDir.toFile().walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = resourcesDir.relativize(file.toPath()).toString()
                resources[relativePath] = file.readText()
            }
        }
        return resources
    }
}
