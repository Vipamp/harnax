package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysUser
import org.apache.ibatis.annotations.*

/**
 * SysUser Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface SysUserMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM sys_user WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): SysUser?

    @Insert(
        """
        INSERT INTO sys_user (
            username, password, nickname, email, phone, gender, avatar, status, is_admin, active, create_time, update_time
        ) VALUES (
            #{username}, #{password}, #{nickname}, #{email}, #{phone}, #{gender}, #{avatar}, #{status}, #{isAdmin}, #{active}, #{createTime}, #{updateTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(sysuser: SysUser): Int

    @Update(
        """
        UPDATE sys_user SET
            username = #{username},
            password = #{password},
            nickname = #{nickname},
            email = #{email},
            phone = #{phone},
            gender = #{gender},
            avatar = #{avatar},
            status = #{status},
            is_admin = #{isAdmin},
            active = #{active},
            update_time = #{updateTime}
        WHERE id = #{id}
        """
    )
    fun updateById(sysuser: SysUser): Int

    @Update("UPDATE sys_user SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
/**
     * 查询用户列表(带条件)
     */
    @Select("""
        SELECT * FROM sys_user 
        WHERE active = 1
        <if test='keyword != null and keyword != ""'>
            AND (username LIKE CONCAT('%', #{keyword}, '%') 
                 OR nickname LIKE CONCAT('%', #{keyword}, '%') 
                 OR email LIKE CONCAT('%', #{keyword}, '%') 
                 OR phone LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test='status != null'>
            AND status = #{status}
        </if>
        ORDER BY update_time DESC
    """)
    fun selectUserList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?
    ): List<SysUser>

    /**
     * 根据用户名查询
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND active = 1 LIMIT 1")
    fun selectByUsername(@Param("username") username: String): SysUser?

    /**
     * 根据 ID 查询(校验 active)
     */
    @Select("SELECT * FROM sys_user WHERE id = #{id} AND active = 1 LIMIT 1")
    fun selectActiveById(@Param("id") id: Long): SysUser?

    /**
     * 更新用户状态
     */
    @Update("UPDATE sys_user SET status = #{status}, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun updateStatus(
        @Param("id") id: Long,
        @Param("status") status: Int
    ): Int

    /**
     * 逻辑删除用户
     */
    @Update("UPDATE sys_user SET active = 0, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun logicalDelete(@Param("id") id: Long): Int
}
