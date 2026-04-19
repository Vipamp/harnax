package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Skill
import org.apache.ibatis.annotations.*

/**
 * Skill Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface SkillMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM skill WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): Skill?

    @Insert(
        """
        INSERT INTO skill (
            name, repository_id, description, skillmd, resources, status, is_public, creator, active, create_time, update_time
        ) VALUES (
            #{name}, #{repositoryId}, #{description}, #{skillmd}, #{resources}, #{status}, #{isPublic}, #{creator}, #{active}, #{createTime}, #{updateTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(skill: Skill): Int

    @Update(
        """
        UPDATE skill SET
            name = #{name},
            repository_id = #{repositoryId},
            description = #{description},
            skillmd = #{skillmd},
            resources = #{resources},
            status = #{status},
            is_public = #{isPublic},
            creator = #{creator},
            active = #{active},
            update_time = #{updateTime}
        WHERE id = #{id}
        """
    )
    fun updateById(skill: Skill): Int

    @Update("UPDATE skill SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
@Select("""
        <script>
            SELECT * FROM skill 
            WHERE active = 1
            AND (is_public = 1 OR creator = #{currentUsername})
            <if test='name != null and name != ""'>
                AND name LIKE CONCAT('%', #{name}, '%')
            </if>
            <if test='repositoryId != null'>
                AND repository_id = #{repositoryId}
            </if>
            <if test='status != null'>
                AND status = #{status}
            </if>
            ORDER BY update_time DESC
        </script>
    """)
    fun selectSkillList(
        @Param("name") name: String?,
        @Param("repositoryId") repositoryId: Long?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String
    ): List<Skill>

    @Select("SELECT * FROM skill WHERE id = #{id} AND active = 1 LIMIT 1")
    fun selectActiveById(@Param("id") id: Long): Skill?

    @Select("SELECT * FROM skill WHERE name = #{name} AND repository_id = #{repositoryId} AND active = 1 LIMIT 1")
    fun selectByNameAndRepo(@Param("name") name: String, @Param("repositoryId") repositoryId: Long): Skill?

    @Update("UPDATE skill SET status = #{status}, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    @Update("UPDATE skill SET active = 0, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun logicalDelete(@Param("id") id: Long): Int

    @Update("""
        <script>
            UPDATE skill 
            <set>
                <if test='description != null'>description = #{description},</if>
                <if test='skillmd != null'>skillmd = #{skillmd},</if>
                <if test='resources != null'>resources = #{resources},</if>
                update_time = NOW()
            </set>
            WHERE id = #{id} AND active = 1
        </script>
    """)
    fun updateSkillFields(
        @Param("id") id: Long,
        @Param("description") description: String?,
        @Param("skillmd") skillmd: String?,
        @Param("resources") resources: String?
    ): Int
}
