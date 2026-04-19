package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysTokenBlacklist
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * SysTokenBlacklist Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface SysTokenBlacklistMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun selectById(@Param("id") id: Long): SysTokenBlacklist?

    fun insert(systokenblacklist: SysTokenBlacklist): Int

    fun updateById(systokenblacklist: SysTokenBlacklist): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    /**
     * 根据 Token 哈希值查询
     *
     * @param tokenHash Token 哈希值
     * @return Token 黑名单记录
     */
    fun selectByTokenHash(@Param("tokenHash") tokenHash: String?): SysTokenBlacklist?
}
