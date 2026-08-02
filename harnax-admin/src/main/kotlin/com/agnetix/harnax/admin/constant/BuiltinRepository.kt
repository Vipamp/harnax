package com.agnetix.harnax.admin.constant

/**
 * Built-in skill repository names with special handling.
 *
 * NOTE: the frontend also references [CLI_SKILLS] (see harnax-webui
 * src/constants/builtinRepository.ts). Keep both in sync when renaming.
 */
object BuiltinRepository {
    /**
     * Repository holding CLI-associated skills.
     * - CLI skill bindings may ONLY reference skills from this repository.
     * - Agents may NOT bind skills from this repository directly (they arrive via CLI).
     * - The repository and its skills are read-only via management APIs.
     */
    const val CLI_SKILLS = "builtin-cli-skills"

    fun isBuiltin(repositoryName: String?): Boolean = repositoryName == CLI_SKILLS
}
