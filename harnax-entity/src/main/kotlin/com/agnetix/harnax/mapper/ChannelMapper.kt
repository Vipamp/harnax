package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Channel
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Channel Mapper interface
 */
@Mapper
interface ChannelMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): Channel?

    fun insert(channel: Channel): Int

    fun updateById(channel: Channel): Int

    fun deleteById(@Param("id") id: Long): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    // ==================== Custom Query Methods ====================

    fun selectChannelList(
        @Param("keyword") keyword: String?,
        @Param("type") type: String?,
        @Param("status") status: Int?,
    ): List<Channel>

    fun selectByCallbackKey(@Param("callbackKey") callbackKey: String): Channel?

    fun selectBySessionId(@Param("sessionId") sessionId: String): Channel?

    /**
     * 查询所有需要在 channel-service 启动时自动建立监听的渠道：
     * enabled=1 AND status=1 AND active=1。
     */
    fun selectAutoStartChannels(): List<Channel>
}
