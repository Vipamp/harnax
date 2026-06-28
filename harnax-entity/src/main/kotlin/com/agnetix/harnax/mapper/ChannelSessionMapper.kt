package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ChannelSession
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * ChannelSession Mapper interface
 */
@Mapper
interface ChannelSessionMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): ChannelSession?

    fun insert(channelSession: ChannelSession): Int

    fun updateById(channelSession: ChannelSession): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================

    fun selectBySessionId(@Param("sessionId") sessionId: String): ChannelSession?

    fun selectByCallbackKey(@Param("callbackKey") callbackKey: String): ChannelSession?
}
