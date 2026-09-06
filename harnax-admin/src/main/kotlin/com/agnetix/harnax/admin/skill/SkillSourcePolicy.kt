package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.entity.SkillRepository

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
}
