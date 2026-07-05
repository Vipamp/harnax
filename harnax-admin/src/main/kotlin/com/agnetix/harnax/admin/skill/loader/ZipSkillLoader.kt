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

    private val log = LoggerFactory.getLogger(ZipSkillLoader::class.java)

    override fun loadSkills(config: Map<String, Any>, tmpDir: Path): List<AgentSkill> {
        val zipPath = config["zipPath"] as? String
            ?: throw IllegalArgumentException("ZIP source config requires 'zipPath'")

        val extractDir = Files.createTempDirectory(tmpDir, "zip-skill-")
        val zipFile = Path.of(zipPath)

        if (!zipFile.exists()) {
            throw IllegalArgumentException("ZIP file not found: $zipPath")
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
        ZipFile(zipFile.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = extractDir.resolve(entry.name).normalize()

                if (!target.startsWith(extractDir)) {
                    throw SecurityException("ZIP entry outside extract directory: ${entry.name}")
                }

                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, target)
                    }
                }
            }
        }
    }

    private fun parseSkillDirectories(extractDir: Path): List<AgentSkill> {
        val skills = mutableListOf<AgentSkill>()
        val rootDirs = extractDir.listDirectoryEntries().filter { it.isDirectory() }

        val searchRoot = if (rootDirs.size == 1) rootDirs.first() else extractDir

        searchRoot.listDirectoryEntries().forEach { entry ->
            if (entry.isDirectory()) {
                val skillMd = entry.resolve("SKILL.md")
                if (Files.exists(skillMd)) {
                    try {
                        val content = Files.readString(skillMd)
                        if (content.isBlank()) {
                            log.warn("Skipping skill {} with empty SKILL.md", entry.name)
                            return@forEach
                        }
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
            val topLevelMd = searchRoot.resolve("SKILL.md")
            if (Files.exists(topLevelMd)) {
                val content = Files.readString(topLevelMd)
                if (content.isNotBlank()) {
                    val description = extractDescription(content)
                    val resources = loadResources(searchRoot.resolve("resources"))

                    val builder = AgentSkill.builder()
                        .name(searchRoot.name)
                        .skillContent(content)
                        .description(description)
                    if (resources.isNotEmpty()) {
                        builder.resources(resources)
                    }
                    skills.add(builder.build())
                }
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
