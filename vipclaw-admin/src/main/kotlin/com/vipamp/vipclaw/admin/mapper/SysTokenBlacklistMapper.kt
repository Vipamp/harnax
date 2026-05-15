package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysTokenBlacklist
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * SysTokenBlacklist Mapper interface
 */
@Mapper
interface SysTokenBlacklistMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): SysTokenBlacklist?

    fun insert(systokenblacklist: SysTokenBlacklist): Int

    fun updateById(systokenblacklist: SysTokenBlacklist): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================
    /**
     * Query by Token Hash
     *
     * @param tokenHash Token hash value
     * @return Token blacklist record
     */
    fun selectByTokenHash(@Param("tokenHash") tokenHash: String?): SysTokenBlacklist?
}
