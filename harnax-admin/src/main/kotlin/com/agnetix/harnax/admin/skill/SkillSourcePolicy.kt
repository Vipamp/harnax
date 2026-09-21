package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.entity.SkillRepository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * Rules that hold for a skill source no matter which API entry point reached it.
 *
 * Two management APIs read and write the same `skill_repository` rows: `/api/admin/skill-sources`
 * and the legacy `/api/admin/skill-repositories` + `/api/admin/skills` pair that the CLI and the
 * mini-program still call. Whenever a check lived in only one of them the two drifted apart — a
 * ZIP source synced through the legacy endpoint answered "ZIP source config requires 'zipPath'"
 * instead of explaining that an upload is one-shot, and a status toggle through either endpoint
 * happily stored `99` in a two-state flag.
 */
object SkillSourcePolicy {

    /**
     * Trims a caller-supplied text value and rejects blank input.
     *
     * Trimming is not cosmetic: `name` is compared against the stored value to decide whether a
     * rename happened, and it participates in a unique index. An untrimmed `" my-skill "` is a
     * different key from `"my-skill"`, so the same skill could be stored twice and the UI would
     * show a trailing space nobody typed.
     *
     * @param noun wording used in the blank message, so each caller keeps its own vocabulary
     * @return the trimmed value, ready to be compared against and written to the database
     */
    fun requireUsableText(value: String?, noun: String): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            throw BizException("$noun cannot be empty")
        }
        return trimmed
    }

    /**
     * Trims a caller-supplied repository name and additionally rejects the platform-reserved
     * builtin name, which [requireUsableText] knows nothing about.
     *
     * @param noun wording used in the blank-name message, so each API keeps its own vocabulary
     */
    fun requireUsableName(name: String?, noun: String): String {
        val trimmed = requireUsableText(name, "$noun name")
        if (BuiltinRepository.isBuiltin(trimmed)) {
            throw BizException("Repository name '${BuiltinRepository.CLI_SKILLS}' is reserved for the platform")
        }
        return trimmed
    }

    /**
     * `status` is a two-state flag. Every consumer compares it with `== 1`, so an out-of-range
     * value does not fail loudly — it silently reads as "disabled" forever and the toggle switch
     * in the UI can no longer bring the row back.
     */
    fun requireStatus(status: Int) {
        if (status != 0 && status != 1) {
            throw BizException("Status must be 0 (disabled) or 1 (enabled), got $status")
        }
    }

    /**
     * Rejects sources with nothing left to read, before a loader is even picked.
     *
     * A ZIP upload is one-shot: the archive is deleted once the request finished. The builtin
     * repository has no remote at all, and asking the registry for its `BUILTIN` source type would
     * only answer "Unsupported skill source type".
     */
    fun requireRefreshable(repository: SkillRepository) {
        when (repository.sourceType) {
            "ZIP" -> throw BizException(
                "ZIP sources are installed once at upload time and keep no archive. " +
                    "Upload the ZIP again to refresh their skills.",
            )
            BuiltinRepository.SOURCE_TYPE -> throw BizException(
                "Repository '${BuiltinRepository.CLI_SKILLS}' is provisioned with the platform and has no source to refresh",
            )
        }
    }

    /**
     * Ceiling on a caller-supplied skill selection. Both selective-install endpoints take an
     * unbounded JSON list and report every name the source does not hold, so one request could
     * otherwise write tens of thousands of failure entries into the stored sync report — a column
     * every repository list read parses and ships to the browser.
     */
    const val MAX_SKILLS_PER_REQUEST = 1000

    /**
     * A skill has to be loadable the moment it is written. `AgentSkill` refuses a blank description
     * or SKILL.md body, so such a row lists fine, binds fine, and then loads as nothing — and the
     * loader's only report is that the id could not be found.
     *
     * Validation never trims: the body is stored as the operator wrote it.
     */
    fun requireContentOnCreate(skillmd: String?, description: String?) {
        requireContent(skillmd, description, absentIsFailure = true)
    }

    /**
     * As [requireContentOnCreate], except that a field the caller left out keeps whatever the row
     * already holds. So an update touching only the status of a legacy-broken skill stays possible —
     * disabling it is usually that very edit.
     */
    fun requireContentOnUpdate(skillmd: String?, description: String?) {
        requireContent(skillmd, description, absentIsFailure = false)
    }

    private fun requireContent(skillmd: String?, description: String?, absentIsFailure: Boolean) {
        if (skillmd == null) {
            if (absentIsFailure) throw BizException("Skill content cannot be empty")
        } else if (skillmd.isBlank()) {
            throw BizException("Skill content cannot be empty")
        }
        if (description == null) {
            if (absentIsFailure) throw BizException("Skill description cannot be empty")
        } else if (description.isBlank()) {
            throw BizException("Skill description cannot be empty")
        }
    }

    /**
     * A blank `resources` means "no bundled files" and is fine. Anything else has to be the object the
     * runtime reads back as `Map<String, String>`: a truncated paste parses into nothing, the loader
     * only warns about it, and the agent is left holding a skill whose files were never delivered.
     */
    fun requireValidResources(resources: String?) {
        if (resources.isNullOrBlank()) return
        try {
            objectMapper.readValue(resources, object : TypeReference<Map<String, String>>() {})
        } catch (e: Exception) {
            throw BizException(
                "Skill resources must be a JSON object mapping file name to file content: ${e.message}",
            )
        }
    }

    private val objectMapper = ObjectMapper()

    /**
     * Normalises a name selection: padded entries are matched against the source by the trimmed name
     * the installer stores, blanks are dropped rather than reported (a failure line naming an empty
     * string tells the caller nothing), and a name listed twice is one skill.
     *
     * `null` survives as `null` because the two answers differ: no selection means the whole source,
     * an empty one means the caller named nothing to store.
     */
    fun normalizeSelection(names: List<String>?): List<String>? {
        if (names == null) return null
        if (names.size > MAX_SKILLS_PER_REQUEST) {
            throw BizException("Too many skills selected, at most $MAX_SKILLS_PER_REQUEST per request")
        }
        return names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
