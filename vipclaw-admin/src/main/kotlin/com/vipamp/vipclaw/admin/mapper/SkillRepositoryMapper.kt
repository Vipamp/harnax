package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SkillRepository
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * SkillRepository Mapper interface
 */
@Mapper
interface SkillRepositoryMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): SkillRepository?

    fun insert(skillrepository: SkillRepository): Int

    fun updateById(skillrepository: SkillRepository): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================
    fun selectRepositoryList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
    ): List<SkillRepository>

    fun selectActiveRepositories(): List<SkillRepository>

    fun selectByName(@Param("name") name: String): SkillRepository?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int
}
