package com.agnetix.harnax.admin.skill.loader

import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Shared parsing helpers for skill source loaders (NPM/ZIP/...).
 */
internal object SkillFileParser {

    fun extractDescription(skillmd: String): String {
        for (line in skillmd.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue
            if (trimmed.isNotEmpty()) return trimmed.take(500)
        }
        return ""
    }

    fun loadResources(resourcesDir: Path): Map<String, String> {
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
