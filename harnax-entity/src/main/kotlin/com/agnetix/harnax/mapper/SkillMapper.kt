package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Skill
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Skill Mapper interface
 */
@Mapper
interface SkillMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): Skill?

    fun insert(skill: Skill): Int

    fun updateById(skill: Skill): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================
    fun selectSkillList(
        @Param("name") name: String?,
        @Param("repositoryId") repositoryId: Long?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long? = null,
    ): List<Skill>

    fun selectByNameAndRepo(@Param("name") name: String, @Param("repositoryId") repositoryId: Long): Skill?

    fun selectByRepositoryId(@Param("repositoryId") repositoryId: Long): List<Skill>

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    fun updateSkillFields(
        @Param("id") id: Long,
        @Param("description") description: String?,
        @Param("skillmd") skillmd: String?,
        @Param("resources") resources: String?,
        @Param("storagePath") storagePath: String? = null,
        @Param("version") version: String? = null,
    ): Int
}
