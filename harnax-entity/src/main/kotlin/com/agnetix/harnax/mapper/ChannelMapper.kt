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

    /**
     * 按 `chn-{uuid}` 取回那条渠道记录（仅 `active = 1`）。
     *
     * 除了 agent-spec 解析，这里还是 admin 回答「这个 chn- 会话属于哪个租户」的那一次读：router 每次
     * 会话级代理调用都可能问到（前面只有五分钟的缓存），所以 `session_id` 上的索引（V28）是这条语句
     * 走等值查找而不是全表扫的前提。
     */
    fun selectBySessionId(@Param("sessionId") sessionId: String): Channel?

    fun selectByAgentId(@Param("agentId") agentId: Long): List<Channel>

    /**
     * 查询所有需要在 channel-service 启动时自动建立监听的渠道：
     * enabled=1 AND status=1 AND active=1。
     */
    fun selectAutoStartChannels(): List<Channel>
}
