package com.agnetix.harnax.admin.skill.loader

import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Shared parsing helpers for skill source loaders (NPM/ZIP/...).
 *
 * `SKILL.md` follows the agentscope convention: an optional YAML frontmatter block
 * delimited by `---` that carries `name` / `description`, followed by the Markdown body.
 * agentscope rejects content without that block, so the loaders here must read the same
 * fields instead of guessing them from the directory name and the first body line.
 */
internal object SkillFileParser {

    private val log = LoggerFactory.getLogger(SkillFileParser::class.java)

    private const val FRONTMATTER_DELIMITER = "---"

    /** Per-file cap: bigger payloads are skipped rather than inflating the resources JSON. */
    private const val MAX_RESOURCE_BYTES = 512 * 1024

    /** Per-skill cap for the whole resources map, kept well below the MEDIUMTEXT column. */
    private const val MAX_TOTAL_RESOURCE_BYTES = 4 * 1024 * 1024

    private const val MAX_DESCRIPTION_LENGTH = 500

    /** Cap for the reason attached to a load failure: exception messages can embed whole files. */
    private const val MAX_FAILURE_REASON_LENGTH = 200

    /** `key: value` — only top-level scalars are understood, nested structures are skipped. */
    private val KEY_VALUE_PATTERN = Regex("^([A-Za-z_][A-Za-z0-9_-]*)\\s*:\\s*(.*)$")

    /** Metadata resolved from the frontmatter; [name] is null when the block omits it. */
    data class SkillMeta(val name: String?, val description: String)

    /**
     * Resolves the skill name and description for a directory-based skill.
     *
     * Frontmatter wins; when it is missing or incomplete the description falls back to the
     * first meaningful body line and the caller's [fallbackName] (the directory name) is kept.
     * A heading-only `SKILL.md` yields the skill name as its description: `AgentSkill.builder()`
     * rejects a blank one, which used to make such skills vanish from the import without a trace.
     */
    fun parseMeta(skillmd: String, fallbackName: String): SkillMeta {
        val frontmatter = parseFrontmatter(skillmd)
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: fallbackName
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() }
            ?: extractDescription(stripFrontmatter(skillmd)).ifBlank { name }
        return SkillMeta(name = name, description = description)
    }

    /**
     * First meaningful line of the Markdown body, with any frontmatter block removed.
     * Headings, horizontal rules, table rows and quote markers carry no description value.
     */
    fun extractDescription(skillmd: String): String {
        for (line in stripFrontmatter(skillmd).lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) continue
            if (trimmed.all { it == '-' || it == '*' || it == '_' || it == '=' }) continue
            if (trimmed.startsWith("|")) continue
            return trimmed.removePrefix(">").trim().take(MAX_DESCRIPTION_LENGTH)
        }
        return ""
    }

    /**
     * Reads every file under `resources/` into a `relativePath -> content` map.
     *
     * Binary files, oversized files and anything beyond the per-skill budget are skipped with
     * a warning: a strict UTF-8 decode would otherwise abort the whole skill import.
     */
    fun loadResources(resourcesDir: Path): Map<String, String> {
        if (!resourcesDir.isDirectory()) return emptyMap()

        val resources = mutableMapOf<String, String>()
        var totalBytes = 0L

        resourcesDir.toFile().walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                // Normalise separators so the stored keys match agentscope's resource paths
                val relativePath = resourcesDir.relativize(file.toPath()).toString().replace('\\', '/')

                val bytes = try {
                    file.readBytes()
                } catch (e: Exception) {
                    log.warn("Skipping unreadable resource {}: {}", relativePath, e.message)
                    return@forEach
                }

                if (bytes.size > MAX_RESOURCE_BYTES) {
                    log.warn("Skipping oversized resource {} ({} bytes, limit {})", relativePath, bytes.size, MAX_RESOURCE_BYTES)
                    return@forEach
                }
                if (totalBytes + bytes.size > MAX_TOTAL_RESOURCE_BYTES) {
                    log.warn("Resource budget exhausted ({} bytes), skipping {}", MAX_TOTAL_RESOURCE_BYTES, relativePath)
                    return@forEach
                }

                val text = decodeUtf8(bytes)
                if (text == null) {
                    log.warn("Skipping non-UTF-8 (binary) resource {}", relativePath)
                    return@forEach
                }

                resources[relativePath] = text
                totalBytes += bytes.size
            }

        return resources
    }

    /**
     * Bounded, human-readable reason for a parse failure, for the `failed` report.
     *
     * Falls back to the exception type because plenty of IO and parsing exceptions carry no message
     * at all, and an empty reason tells the operator nothing about which skill broke or why.
     */
    fun failureReason(e: Exception): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return if (raw.length > MAX_FAILURE_REASON_LENGTH) {
            raw.take(MAX_FAILURE_REASON_LENGTH) + "..."
        } else {
            raw
        }
    }

    /** Body without the leading frontmatter block; returns the input unchanged when absent. */
    fun stripFrontmatter(skillmd: String): String {
        val lines = skillmd.lines()
        if (lines.isEmpty() || lines.first().trim() != FRONTMATTER_DELIMITER) return skillmd
        val endIndex = (1 until lines.size).firstOrNull { lines[it].trim() == FRONTMATTER_DELIMITER } ?: return skillmd
        return lines.subList(endIndex + 1, lines.size).joinToString("\n")
    }

    /**
     * Minimal frontmatter reader covering the shapes skills actually use: `key: value`,
     * block scalars (`key: |` / `key: >`) and plain multi-line scalars continued by indentation.
     * Returns an empty map when the document has no frontmatter block.
     */
    private fun parseFrontmatter(skillmd: String): Map<String, String> {
        val lines = skillmd.lines()
        if (lines.isEmpty() || lines.first().trim() != FRONTMATTER_DELIMITER) return emptyMap()
        val endIndex = (1 until lines.size).firstOrNull { lines[it].trim() == FRONTMATTER_DELIMITER }
            ?: return emptyMap()

        val fields = mutableMapOf<String, String>()
        var currentKey: String? = null
        val buffer = StringBuilder()

        fun flush() {
            val key = currentKey ?: return
            val value = buffer.toString().trim()
            if (value.isNotEmpty()) {
                fields[key] = value
            }
            currentKey = null
            buffer.setLength(0)
        }

        for (raw in lines.subList(1, endIndex)) {
            val match = KEY_VALUE_PATTERN.matchEntire(raw)
            if (match != null && !raw.startsWith(" ") && !raw.startsWith("\t")) {
                flush()
                val value = match.groupValues[2].trim()
                currentKey = match.groupValues[1]
                // `|` and `>` introduce a block scalar whose content lives on the next lines
                if (value == "|" || value == ">" || value == "|-" || value == ">-" || value.isEmpty()) {
                    buffer.setLength(0)
                } else {
                    buffer.append(unquote(value))
                }
                continue
            }

            // Indented continuation of the current scalar; list items belong to nested
            // structures the skill metadata does not use, so they are ignored.
            if (currentKey != null && raw.isNotBlank() && !raw.trim().startsWith("-")) {
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(raw.trim())
            }
        }
        flush()

        return fields
    }

    private fun unquote(value: String): String = when {
        value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") ->
            value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\n", "\n")
        value.length >= 2 && value.startsWith("'") && value.endsWith("'") ->
            value.substring(1, value.length - 1).replace("''", "'")
        else -> value
    }

    /** Strict UTF-8 decode: null when the payload is binary rather than silently lossy. */
    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
