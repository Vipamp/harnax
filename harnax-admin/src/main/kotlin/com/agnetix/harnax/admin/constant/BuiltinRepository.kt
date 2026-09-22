package com.agnetix.harnax.admin.constant

/**
 * Built-in skill repository names with special handling.
 *
 * NOTE: the frontend also references [CLI_SKILLS] (see harnax-webui
 * src/constants/builtinRepository.ts). Keep both in sync when renaming.
 */
object BuiltinRepository {
    /**
     * Repository holding the skills shipped inside CLI packages.
     * - `CliPackageAutoRegistrar` upserts each package's `skill/SKILL.md` here and stores the row id
     *   on `cli.skill_id`; the package, not an operator, is the writer.
     * - Agents may NOT bind skills from this repository directly (they arrive via the CLI).
     * - The repository and its skills are read-only via management APIs.
     */
    const val CLI_SKILLS = "builtin-cli-skills"

    /**
     * `source_type` of the builtin row, set by `V15__skill_source_integrity.sql`.
     *
     * It is deliberately not one of GIT / NPM / ZIP: the repository is provisioned with the
     * platform, has no remote to fetch from and no loader.
     */
    const val SOURCE_TYPE = "BUILTIN"

    fun isBuiltin(repositoryName: String?): Boolean = repositoryName == CLI_SKILLS
}
