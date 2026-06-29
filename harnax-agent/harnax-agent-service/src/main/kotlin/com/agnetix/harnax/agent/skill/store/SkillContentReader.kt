package com.agnetix.harnax.agent.skill.store

interface SkillContentReader {

    fun load(storagePath: String): SkillContentData

    fun exists(storagePath: String): Boolean
}
