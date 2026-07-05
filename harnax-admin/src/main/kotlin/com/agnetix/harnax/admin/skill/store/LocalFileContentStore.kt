package com.agnetix.harnax.admin.skill.store

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
class LocalFileContentStore(
    @Value("\${skill.storage.base-path:./data/skill-store}") private val basePath: String,
) : SkillContentStore {

    private val log = LoggerFactory.getLogger(LocalFileContentStore::class.java)

    override fun save(repositoryId: Long, skillName: String, content: SkillContent): String {
        val dir = Path.of(basePath, repositoryId.toString(), skillName)
        Files.createDirectories(dir)

        Files.writeString(dir.resolve("SKILL.md"), content.skillmd)

        if (content.resources.isNotEmpty()) {
            val resourcesDir = dir.resolve("resources")
            Files.createDirectories(resourcesDir)
            content.resources.forEach { (path, bytes) ->
                val file = resourcesDir.resolve(path)
                Files.createDirectories(file.parent)
                Files.write(file, bytes)
            }
        }

        val storagePath = "$repositoryId/$skillName"
        log.debug("Saved skill content to local path: {}", storagePath)
        return storagePath
    }

    override fun load(storagePath: String): SkillContent {
        val dir = Path.of(basePath, storagePath)
        if (!dir.isDirectory()) {
            throw RuntimeException("Skill content not found at path: $storagePath")
        }

        val skillmd = Files.readString(dir.resolve("SKILL.md"))
        val resources = loadResources(dir.resolve("resources"))
        return SkillContent(skillmd, resources)
    }

    override fun delete(storagePath: String) {
        val dir = Path.of(basePath, storagePath)
        if (dir.isDirectory()) {
            dir.toFile().deleteRecursively()
            log.debug("Deleted skill content at local path: {}", storagePath)
        }
    }

    override fun exists(storagePath: String): Boolean {
        val dir = Path.of(basePath, storagePath)
        return dir.exists() && dir.resolve("SKILL.md").exists()
    }

    private fun loadResources(resourcesDir: Path): Map<String, ByteArray> {
        if (!resourcesDir.isDirectory()) return emptyMap()
        val resources = mutableMapOf<String, ByteArray>()
        resourcesDir.toFile().walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = resourcesDir.relativize(file.toPath()).toString()
                resources[relativePath] = file.readBytes()
            }
        }
        return resources
    }
}
