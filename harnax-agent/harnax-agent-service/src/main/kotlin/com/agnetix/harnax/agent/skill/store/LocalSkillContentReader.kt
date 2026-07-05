package com.agnetix.harnax.agent.skill.store

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

@Component
@ConditionalOnProperty(name = ["skill.storage.type"], havingValue = "local", matchIfMissing = true)
class LocalSkillContentReader(
    @Value($$"${skill.storage.base-path:./data/skill-store}") private val basePath: String,
) : SkillContentReader {

    private val log = LoggerFactory.getLogger(LocalSkillContentReader::class.java)

    override fun load(storagePath: String): SkillContentData {
        val dir = Path.of(basePath, storagePath)
        if (!dir.isDirectory()) {
            throw RuntimeException("Skill content not found at path: $storagePath")
        }

        val skillmd = Files.readString(dir.resolve("SKILL.md"))
        val resources = loadResources(dir.resolve("resources"))
        return SkillContentData(skillmd, resources)
    }

    override fun exists(storagePath: String): Boolean {
        val dir = Path.of(basePath, storagePath)
        return dir.exists() && dir.resolve("SKILL.md").exists()
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
