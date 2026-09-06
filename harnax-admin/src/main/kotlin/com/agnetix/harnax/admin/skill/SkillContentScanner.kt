package com.agnetix.harnax.admin.skill

import org.slf4j.LoggerFactory

/**
 * Screens imported skill content for destructive shell payloads.
 *
 * Skills are not inert documentation: with `autoUploadSkill` the bundled `resources/` files land
 * in the agent workspace where shell and file tools can execute them, so an imported package is
 * effectively untrusted code. agentscope scans skills created through its own tooling
 * (`SkillSecurityScanner`), but the admin import path (GIT / NPM / ZIP) used to bypass any check.
 *
 * The scanner never rejects an import — a documentation skill may legitimately quote a dangerous
 * command. A hit downgrades the skill to `status = 0` so a human reviews and enables it explicitly.
 */
object SkillContentScanner {

    private val log = LoggerFactory.getLogger(SkillContentScanner::class.java)

    /** One suspicious occurrence: which file matched, the rule id and a short excerpt. */
    data class Finding(
        val resource: String,
        val ruleId: String,
        val reason: String,
        val excerpt: String,
    )

    private class Rule(val id: String, val reason: String, val regex: Regex)

    /**
     * Command boundaries.
     *
     * Skills quote shell snippets inside Markdown inline code (`` `rm -rf /` ``), fenced blocks and
     * plain sentences, so matching only on shell metacharacters misses most of them: any character
     * that cannot be part of a command name also starts or ends one. A trailing `-` is excluded on
     * purpose so hyphenated names such as `x-rm` are not read as a bare `rm`.
     */
    private const val COMMAND_START = """(?:^|[^\w-])"""

    private const val COMMAND_END = """(?:[^\w]|$)"""

    private val RULES = listOf(
        Rule(
            "recursive-root-delete",
            "recursively deletes a root-level path",
            Regex("""${COMMAND_START}rm\s+(?:-\w+\s+)*-\w*[rf]\w*\s+(?:-\w+\s+)*(?:/|/\*|~|\${'$'}HOME|\${'$'}\{HOME\})${COMMAND_END}"""),
        ),
        Rule(
            "windows-drive-wipe",
            "wipes a Windows drive",
            Regex("""${COMMAND_START}(?:del|erase)\s+/[sfq]\s+\w:\\|${COMMAND_START}format\s+\w:\s*(?:/q)?""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "disk-overwrite",
            "overwrites a block device or filesystem",
            Regex("""${COMMAND_START}(?:dd\s+if=\S+\s+of=/dev/|mkfs(?:\.\w+)?\s+/dev/)"""),
        ),
        Rule(
            "remote-pipe-to-shell",
            "pipes a remote payload straight into a shell",
            Regex("""(?:curl|wget)\b[^\n|]*\|\s*(?:sudo\s+)?(?:ba|z|da)?sh\b"""),
        ),
        Rule(
            "reverse-shell",
            "opens a reverse shell",
            Regex("""/dev/tcp/|\bnc\b[^\n]*\s-e\s|bash\s+-i\s+[>&]+\s*/dev/"""),
        ),
        Rule(
            "fork-bomb",
            "contains a fork bomb",
            Regex(""":\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;"""),
        ),
        Rule(
            "permission-escalation-of-root",
            "recursively opens permissions on a system path",
            Regex("""${COMMAND_START}chmod\s+(?:-\w+\s+)*-R\s+(?:0?777|a\+rwx)\s+/${COMMAND_END}"""),
        ),
        Rule(
            "history-and-audit-tampering",
            "erases shell history or audit logs",
            Regex("""(?:>|${COMMAND_START}rm\s+-\w+)\s*(?:~|\${'$'}HOME|\${'$'}\{HOME\})/\.bash_history|${COMMAND_START}(?:history\s+-c|unset\s+HISTFILE)\b"""),
        ),
        Rule(
            "credential-harvest-upload",
            "exfiltrates local credentials to a remote endpoint",
            Regex("""(?:curl|wget)\b[^\n]*(?:--data|-d|-F|--upload-file|-T)\b[^\n]*(?:credentials|\.ssh/|\.aws/|\.netrc|id_rsa)""", RegexOption.IGNORE_CASE),
        ),
    )

    /**
     * Scans the SKILL.md body plus every bundled resource file.
     *
     * @param skillmd full Markdown content
     * @param resources `relativePath -> content` map
     * @return findings, empty when nothing matched
     */
    fun scan(skillmd: String?, resources: Map<String, String>?): List<Finding> {
        val findings = mutableListOf<Finding>()
        val targets = linkedMapOf<String, String>()
        if (!skillmd.isNullOrBlank()) {
            targets["SKILL.md"] = skillmd
        }
        resources?.forEach { (path, content) -> if (content.isNotBlank()) targets[path] = content }

        targets.forEach { (resource, content) ->
            RULES.forEach { rule ->
                val match = rule.regex.find(content) ?: return@forEach
                findings.add(
                    Finding(
                        resource = resource,
                        ruleId = rule.id,
                        reason = rule.reason,
                        excerpt = excerptOf(content, match.range.first),
                    ),
                )
            }
        }

        if (findings.isNotEmpty()) {
            log.warn(
                "Skill content scan flagged {} issue(s): {}",
                findings.size,
                findings.joinToString("; ") { "${it.resource}:${it.ruleId}" },
            )
        }
        return findings
    }

    private fun excerptOf(content: String, startIndex: Int): String {
        val from = maxOf(0, startIndex - 20)
        val to = minOf(content.length, startIndex + 120)
        return content.substring(from, to).replace('\n', ' ').trim()
    }
}
