package com.vipamp.vipclaw.admin.util

import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.skill.repository.GitSkillRepository
import java.nio.file.Path

/**
 * @Description: SkillRepositoryLoader
 */
object GitSkillLoader {

    fun loadSkillsFromGit(url: String, branch: String, tmpDir: String, name: String): List<AgentSkill> = loadSkillsFromGit(
        url,
        branch,
        Path.of(tmpDir).resolve(name),
    )

    fun loadSkillsFromGit(url: String, branch: String, skillDir: Path): List<AgentSkill> = GitSkillRepository(
        url,
        branch,
        skillDir,
    ).allSkills
}
