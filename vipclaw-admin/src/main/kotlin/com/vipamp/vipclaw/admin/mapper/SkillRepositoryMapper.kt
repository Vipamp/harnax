package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SkillRepository
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * SkillRepository Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface SkillRepositoryMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun selectById(@Param("id") id: Long): SkillRepository?

    fun insert(skillrepository: SkillRepository): Int

    fun updateById(skillrepository: SkillRepository): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    fun selectRepositoryList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
    ): List<SkillRepository>

    fun selectActiveRepositories(): List<SkillRepository>

    fun selectByName(@Param("name") name: String): SkillRepository?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int
}
