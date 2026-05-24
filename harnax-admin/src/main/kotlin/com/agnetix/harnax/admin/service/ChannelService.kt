package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.ChannelCreateRequest
import com.agnetix.harnax.admin.dto.ChannelResponse
import com.agnetix.harnax.admin.dto.ChannelUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.Channel

/**
 * Channel service interface
 */
interface ChannelService {

    /**
     * Query Channel list with pagination
     *
     * @param keyword  Fuzzy search field
     * @param type     Type filter
     * @param status   Status filter
     * @param pageNum  Current page number
     * @param pageSize Page size
     * @return Paginated result
     */
    fun page(keyword: String?, type: String?, status: Int?, pageNum: Int, pageSize: Int): Page<Channel>

    /**
     * Get single Channel details
     *
     * @param id Channel ID
     * @return Channel entity
     */
    fun getChannel(id: Long): Channel?

    /**
     * Create Channel
     *
     * @param request Channel create request object
     * @return Create result
     */
    fun createChannel(request: ChannelCreateRequest): Boolean

    /**
     * Update Channel
     *
     * @param id      Channel ID
     * @param request Channel update request object
     * @return Update result
     */
    fun updateChannel(id: Long, request: ChannelUpdateRequest): Boolean

    /**
     * Toggle Channel enable status
     *
     * @param id     Channel ID
     * @param status Enable status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun toggleChannelStatus(id: Long, status: Int): Boolean

    /**
     * Delete Channel
     *
     * @param id Channel ID
     * @return Delete result
     */
    fun deleteChannel(id: Long): Boolean

    /**
     * Query Channel by callback key
     *
     * @param callbackKey Callback key
     * @return Channel entity
     */
    fun getByCallbackKey(callbackKey: String): Channel?

    /**
     * Convert Channel entity to response DTO
     *
     * @param channel Channel entity
     * @return Response DTO
     */
    fun convertToResponse(channel: Channel): ChannelResponse
}
