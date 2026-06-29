package com.agnetix.harnax.admin.skill.loader

import io.agentscope.core.skill.AgentSkill
import java.nio.file.Path

interface SkillLoader {
    val sourceType: String

    fun loadSkills(config: Map<String, Any>, tmpDir: Path): List<AgentSkill>

    fun validateConfig(config: Map<String, Any>)
}
