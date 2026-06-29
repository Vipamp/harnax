package com.agnetix.harnax.admin.skill.store

interface SkillContentStore {

    fun save(repositoryId: Long, skillName: String, content: SkillContent): String

    fun load(storagePath: String): SkillContent

    fun delete(storagePath: String)

    fun exists(storagePath: String): Boolean
}
