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
)
