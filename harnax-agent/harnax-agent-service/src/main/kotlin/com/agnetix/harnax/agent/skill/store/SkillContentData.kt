package com.agnetix.harnax.agent.skill.store

data class SkillContentData(
    val skillmd: String,
    val resources: Map<String, String> = emptyMap()
)
