package com.agnetix.harnax.admin.skill.loader

import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.skill.repository.GitSkillRepository
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class GitSkillLoader : SkillLoader {
    override val sourceType = "GIT"

    override fun loadSkills(config: Map<String, Any>, tmpDir: Path): List<AgentSkill> {
        val url = config["url"] as? String
            ?: throw IllegalArgumentException("Git source config requires 'url'")
        val branch = (config["branch"] as? String) ?: "main"
        val skillDir = Files.createTempDirectory(tmpDir, "git-skill-")
        return GitSkillRepository(url, branch, skillDir).allSkills
    }

    override fun validateConfig(config: Map<String, Any>) {
        if (config["url"].isNullOrEmpty()) {
            throw IllegalArgumentException("Git source config requires 'url'")
        }
    }

    private fun Any?.isNullOrEmpty(): Boolean = this == null || (this is String && this.isBlank())
}
