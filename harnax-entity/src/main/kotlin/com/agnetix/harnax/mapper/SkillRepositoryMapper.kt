package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SkillRepository
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
        @Param("tenantId") tenantId: Long? = null,
    ): List<SkillRepository>

    fun selectActiveRepositories(@Param("tenantId") tenantId: Long? = null): List<SkillRepository>

    fun selectByName(@Param("name") name: String, @Param("tenantId") tenantId: Long? = null): SkillRepository?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int
}
