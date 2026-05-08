package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysUser
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/**
 * SysUser Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface SysUserMapper {

    // ==================== 基础 CRUD 方法 ====================

    /**
     * 根据 ID 查询用户
     *
     * @param id 用户 ID
     * @return 用户实体
     */
    fun selectById(@Param("id") id: Long): SysUser?

    /**
     * 插入用户
     *
     * @param sysuser 用户实体
     * @return 影响行数
     */
    fun insert(sysuser: SysUser): Int

    /**
     * 根据 ID 更新用户
     *
     * @param sysuser 用户实体
     * @return 影响行数
     */
    fun updateById(sysuser: SysUser): Int

    /**
     * 根据 ID 删除用户（逻辑删除）
     *
     * @param id 用户 ID
     * @return 影响行数
     */
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    /**
     * 查询用户列表（带条件）
     *
     * @param keyword 关键字（搜索 username/nickname/email/phone）
     * @param status 用户状态
     * @param tenantId 租户ID过滤
     * @return 用户列表
     */
    fun selectUserList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("tenantId") tenantId: Long?,
    ): List<SysUser>

    /**
     * 根据用户名查询
     *
     * @param username 用户名
     * @return 用户实体
     */
    fun selectByUsername(@Param("username") username: String): SysUser?

    /**
     * 根据手机号查询
     *
     * @param phone 手机号
     * @return 用户实体
     */
    fun selectByPhone(@Param("phone") phone: String): SysUser?

    /**
     * 根据邮箱查询
     *
     * @param email 邮箱
     * @return 用户实体
     */
    fun selectByEmail(@Param("email") email: String): SysUser?

    /**
     * 更新用户状态
     *
     * @param id 用户 ID
     * @param status 新状态
     * @return 影响行数
     */
    fun updateStatus(
        @Param("id") id: Long,
        @Param("status") status: Int,
    ): Int

    /**
     * 更新用户最近一次登录时间
     *
     * @param id 用户 ID
     * @param lastLoginTime 登录时间
     * @return 影响行数
     */
    fun updateLastLoginTime(
        @Param("id") id: Long,
        @Param("lastLoginTime") lastLoginTime: LocalDateTime,
    ): Int
}
