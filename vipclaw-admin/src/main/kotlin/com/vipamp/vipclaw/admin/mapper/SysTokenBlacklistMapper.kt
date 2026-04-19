package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysTokenBlacklist
import org.apache.ibatis.annotations.*

/**
 * SysTokenBlacklist Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface SysTokenBlacklistMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM sys_token_blacklist WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): SysTokenBlacklist?

    @Insert(
        """
        INSERT INTO sys_token_blacklist (
            token, token_hash, username, user_id, reason, expire_time, create_time, create_ip, token_hash, user_id, expire_time, create_time, create_ip
        ) VALUES (
            #{token}, #{tokenHash}, #{username}, #{userId}, #{reason}, #{expireTime}, #{createTime}, #{createIp}, #{tokenHash}, #{userId}, #{expireTime}, #{createTime}, #{createIp}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(systokenblacklist: SysTokenBlacklist): Int

    @Update(
        """
        UPDATE sys_token_blacklist SET
            token = #{token},
            token_hash = #{tokenHash},
            username = #{username},
            user_id = #{userId},
            reason = #{reason},
            expire_time = #{expireTime},
            create_ip = #{createIp},
            token_hash = #{tokenHash},
            user_id = #{userId},
            expire_time = #{expireTime},
            create_ip = #{createIp}
        WHERE id = #{id}
        """
    )
    fun updateById(systokenblacklist: SysTokenBlacklist): Int

    @Update("UPDATE sys_token_blacklist SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
/**
     * 根据 Token 哈希值查询
     *
     * @param tokenHash Token 哈希值
     * @return Token 黑名单记录
     */
    @Select("SELECT * FROM sys_token_blacklist WHERE token_hash = #{tokenHash} AND expire_time > NOW()")
    fun selectByTokenHash(@Param("tokenHash") tokenHash: String?): SysTokenBlacklist?
}
