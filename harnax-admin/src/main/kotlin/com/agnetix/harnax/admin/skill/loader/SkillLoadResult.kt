package com.agnetix.harnax.admin.skill.loader

import io.agentscope.core.skill.AgentSkill

/**
 * Everything one loader found in a source, including what it could not read.
 *
 * A directory holding an unparseable `SKILL.md` used to vanish right here: the loaders logged a
 * warning and dropped it, so the installer went on to report success while the skill was missing.
 * Carrying the reason next to the skills lets `SkillInstaller` fold these into the same `failed`
 * list as the persistence errors, which keeps "the source holds N, M were stored, K failed"
 * adding up for the operator.
 */
data class SkillLoadResult(
    val skills: List<AgentSkill>,
    val failures: List<SkillLoadFailure> = emptyList(),
)

/**
 * A directory that looked like a skill but could not be turned into one.
 *
 * [name] is the directory name rather than the name declared in the frontmatter: parsing is exactly
 * what failed, so the directory is the only identifier guaranteed to exist.
 *
 * A directory with no `SKILL.md` at all is *not* a failure. An npm package or an archive routinely
 * holds directories that were never meant to be skills (`node_modules`, shared assets, a `docs`
 * folder), and reporting those would bury the handful of real problems.
 */
data class SkillLoadFailure(
    val name: String,
    val reason: String,
) {
    companion object {
        /**
         * Pseudo-name for a failure that belongs to the source as a whole rather than to one skill
         * directory: a clone that never finished has no skill to blame it on. `SkillInstaller`
         * turns it into the response's `sourceError` so the per-skill list stays a list of skills.
         */
        const val WHOLE_SOURCE = "<source>"

        /**
         * Pseudo-name for "the source opened, was read to the end, and holds no skill". Distinct from
         * [WHOLE_SOURCE] because the two need different fixes: a broken address versus a repository
         * or archive shaped differently than the loader expects.
         */
        const val EMPTY_SOURCE = "<empty>"

        /** Whether [name] is one of these two pseudo-names rather than a skill directory. */
        fun isSourceLevel(name: String): Boolean = name == WHOLE_SOURCE || name == EMPTY_SOURCE
    }
}
