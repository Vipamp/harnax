package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Channel
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Channel Mapper 接口
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Mapper
interface ChannelMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun selectById(@Param("id") id: Long): Channel?

    fun insert(channel: Channel): Int

    fun updateById(channel: Channel): Int

    fun deleteById(@Param("id") id: Long): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    // ==================== 自定义查询方法 ====================

    fun selectChannelList(
        @Param("keyword") keyword: String?,
        @Param("type") type: String?,
        @Param("status") status: Int?
    ): List<Channel>

    fun selectByCallbackKey(@Param("callbackKey") callbackKey: String): Channel?
}
