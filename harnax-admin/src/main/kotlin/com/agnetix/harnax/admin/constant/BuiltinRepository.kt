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
     * Where an approved draft lands: one repository of this name per tenant, provisioned by the first
     * approval that tenant ever grants.
     *
     * `skill` is only unique on `(repository_id, active_name)`, so promotion needs a home for the row.
     * Per tenant rather than one platform-wide row like [CLI_SKILLS]: the shared builtin is exempt from
     * the tenant predicate in every read path (`SkillBindingResolver.deliverable`, `SkillMapper.selectSkillList`),
     * and a promoted agent skill must never be. A tenant-owned row also keeps its own name visible in that
     * tenant's skill list, which a row owned by the seeding tenant would not.
     */
    const val AGENT_SKILLS = "agent-skills"

    /**
     * `source_type` of the builtin row, as the schema baseline's initial data seeds it.
     *
     * It is deliberately not one of GIT / NPM / ZIP: the repository is provisioned with the
     * platform, has no remote to fetch from and no loader.
     */
    const val SOURCE_TYPE = "BUILTIN"

    /**
     * Reserved names no operator may claim for a source of their own.
     *
     * Only the *name* of [AGENT_SKILLS] is reserved. Everything else about the CLI repository — not
     * bindable, read-only through the management APIs, visible across tenants — would be wrong for the
     * landing repository, whose skills are ordinary ones a reviewer decided to publish. So [isBuiltin]
     * stays CLI-specific and the two sets are not the same thing; widening it is the mistake to avoid.
     */
    val RESERVED_NAMES = setOf(CLI_SKILLS, AGENT_SKILLS)

    fun isBuiltin(repositoryName: String?): Boolean = repositoryName == CLI_SKILLS
}
