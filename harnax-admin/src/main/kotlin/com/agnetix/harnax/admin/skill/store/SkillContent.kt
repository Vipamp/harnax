package com.agnetix.harnax.admin.skill.store

data class SkillContent(
    val skillmd: String,
    val resources: Map<String, ByteArray> = emptyMap()
)
