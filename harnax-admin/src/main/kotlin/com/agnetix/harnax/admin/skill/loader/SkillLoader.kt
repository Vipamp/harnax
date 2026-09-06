package com.agnetix.harnax.admin.skill.loader

import java.nio.file.Path

interface SkillLoader {
    val sourceType: String

    /**
     * Reads every skill the source exposes under [tmpDir], together with the directories that held
     * a `SKILL.md` which could not be parsed.
     *
     * Whole-source problems are still thrown rather than reported: a clone that fails, an `npm
     * install` that exits non-zero or an archive that will not open leave nothing to attribute per
     * skill, and the caller already turns those into one error for the operator.
     */
    fun loadSkills(config: Map<String, Any>, tmpDir: Path): SkillLoadResult

    fun validateConfig(config: Map<String, Any>)
}
