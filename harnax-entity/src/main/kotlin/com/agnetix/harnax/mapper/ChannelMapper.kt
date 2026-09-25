package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.entity.dto.ChannelSessionOwner
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

    /**
     * The channels of one tenant, and only ever one tenant.
     *
     * [tenantId] is a required predicate rather than an optional `<if>`: the caller's workspace is not
     * something a request may filter its way out of, and every other read of this table
     * ([selectById] through the service) is scoped the same way.
     */
    fun selectChannelList(
        @Param("keyword") keyword: String?,
        @Param("type") type: String?,
        @Param("status") status: Int?,
        @Param("tenantId") tenantId: Long,
    ): List<Channel>

    fun selectByCallbackKey(@Param("callbackKey") callbackKey: String): Channel?

    /**
     * The live channel (`active = 1`) carrying this `chn-{uuid}` id.
     *
     * Used where the caller wants the channel's *configuration* — agent-spec resolution, and the
     * capability and permission-mode writes, none of which should act on a row the admin has deleted.
     * Session ownership is answered by [selectOwnerBySessionId] instead, which is the same lookup
     * without the `active` filter.
     *
     * The index on `channel.session_id` is what keeps this an equality lookup rather than a full scan;
     * the router asks one session per call it proxies, with only a five-minute cache in front.
     */
    fun selectBySessionId(@Param("sessionId") sessionId: String): Channel?

    /**
     * The owner recorded for this `chn-{uuid}` id, whether or not the channel is still active.
     *
     * No `active` predicate, on purpose — and [selectBySessionId] is left filtering rather than relaxed,
     * because its other callers do want a deleted channel to be gone. `deleteById` is a soft delete, so
     * the row keeps its `tenant_id` after the channel is deleted, while the session and sandbox behind it
     * are documented as not being cleaned up. Answering the ownership question through an active-filtered
     * read would make a deleted channel look like nobody's session, and the router passes what it cannot
     * attribute to a tenant — so deleting a row would erase accountability for reading it, for as long as
     * the router still routes to that session. A channel row always has an owner; this is the read that
     * says who, so the "unknown means pass" default stays what it is supposed to cover: an id that was
     * never a channel.
     *
     * Null means no row exists for the id at all, which — `chn-` ids being minted and inserted with
     * their channel — is an id this admin never issued.
     */
    fun selectOwnerBySessionId(@Param("sessionId") sessionId: String): ChannelSessionOwner?

    fun selectByAgentId(@Param("agentId") agentId: Long): List<Channel>

    /**
     * 查询所有需要在 channel-service 启动时自动建立监听的渠道：
     * enabled=1 AND status=1 AND active=1。
     */
    fun selectAutoStartChannels(): List<Channel>
}
