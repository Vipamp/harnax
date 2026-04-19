package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SkillRepository
import org.apache.ibatis.annotations.*

/**
 * SkillRepository Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface SkillRepositoryMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM skill_repository WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): SkillRepository?

    @Insert(
        """
        INSERT INTO skill_repository (
            name, url, branch, description, status, is_public, creator, active, create_time, update_time
        ) VALUES (
            #{name}, #{url}, #{branch}, #{description}, #{status}, #{isPublic}, #{creator}, #{active}, #{createTime}, #{updateTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(skillrepository: SkillRepository): Int

    @Update(
        """
        UPDATE skill_repository SET
            name = #{name},
            url = #{url},
            branch = #{branch},
            description = #{description},
            status = #{status},
            is_public = #{isPublic},
            creator = #{creator},
            active = #{active},
            update_time = #{updateTime}
        WHERE id = #{id}
        """
    )
    fun updateById(skillrepository: SkillRepository): Int

    @Update("UPDATE skill_repository SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
@Select("""
        <script>
            SELECT * FROM skill_repository 
            WHERE active = 1
            AND (is_public = 1 OR creator = #{currentUsername})
            <if test='name != null and name != ""'>
                AND name LIKE CONCAT('%', #{name}, '%')
            </if>
            <if test='status != null'>
                AND status = #{status}
            </if>
            ORDER BY status DESC, update_time DESC
        </script>
    """)
    fun selectRepositoryList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String
    ): List<SkillRepository>

    @Select("SELECT * FROM skill_repository WHERE active = 1 AND status = 1 ORDER BY update_time DESC")
    fun selectActiveRepositories(): List<SkillRepository>

    @Select("SELECT * FROM skill_repository WHERE id = #{id} AND active = 1 LIMIT 1")
    fun selectActiveById(@Param("id") id: Long): SkillRepository?

    @Select("SELECT * FROM skill_repository WHERE name = #{name} AND active = 1 LIMIT 1")
    fun selectByName(@Param("name") name: String): SkillRepository?

    @Update("UPDATE skill_repository SET status = #{status}, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    @Update("UPDATE skill_repository SET active = 0, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun logicalDelete(@Param("id") id: Long): Int
}
