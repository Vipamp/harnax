package com.vipamp.vipclaw.admin.util

import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.skill.repository.GitSkillRepository
import java.nio.file.Path

/**
 * @Author: heqingsong
 * @Date: 2026/4/26
 * @Description: SkillRepositoryLoader
 * @Project: vipclaw
 */
object GitSkillLoader {

    fun loadSkillsFromGit(url: String, branch: String, tmpDir: String, name: String): List<AgentSkill> {
        return loadSkillsFromGit(
            url,
            branch,
            Path.of(tmpDir).resolve(name)
        )
    }

    fun loadSkillsFromGit(url: String, branch: String, skillDir: Path): List<AgentSkill> {
        return GitSkillRepository(
            url,
            branch,
            skillDir
        ).allSkills
    }
}
