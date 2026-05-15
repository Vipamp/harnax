package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.context.TenantContext
import com.vipamp.vipclaw.admin.dto.ChannelCreateRequest
import com.vipamp.vipclaw.admin.dto.ChannelResponse
import com.vipamp.vipclaw.admin.dto.ChannelUpdateRequest
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.Agent
import com.vipamp.vipclaw.admin.entity.Channel
import com.vipamp.vipclaw.admin.mapper.ChannelMapper
import com.vipamp.vipclaw.admin.service.AgentService
import com.vipamp.vipclaw.admin.service.ChannelService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.*

/**
 * Channel service implementation
 */
@Service
class ChannelServiceImpl(
    private val channelMapper: ChannelMapper,
    private val agentService: AgentService,
) : ChannelService {

    private val log = LoggerFactory.getLogger(ChannelServiceImpl::class.java)

    @Value("\${app.base-url:http://localhost:8080}")
    private lateinit var baseUrl: String

    override fun page(
        keyword: String?,
        type: String?,
        status: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<Channel> {
        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(channelMapper.selectChannelList(keyword, type, status))
    }

    override fun getChannel(id: Long): Channel? = channelMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createChannel(request: ChannelCreateRequest): Boolean = try {
        val channel = Channel()
        channel.name = request.name!!
        channel.type = request.type!!
        channel.agentId = request.agentId!!
        channel.webhookUrl = request.webhookUrl!!
        channel.token = request.token!!
        channel.encodingAesKey = request.encodingAesKey!!
        channel.appId = request.appId!!
        channel.appSecret = request.appSecret!!
        channel.description = request.description!!
        channel.status = request.status ?: 1

        // Set tenant ID
        channel.tenantId = TenantContext.getTenantId() ?: 1

        // Generate unique callback key
        val callbackKey = generateCallbackKey(request.type)
        channel.callbackKey = callbackKey

        channel.createTime = LocalDateTime.now()
        channel.updateTime = LocalDateTime.now()
        channelMapper.insert(channel)
        true
    } catch (e: Exception) {
        log.error("Failed to create Channel", e)
        throw RuntimeException("Failed to create Channel: ${e.message}")
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateChannel(id: Long, request: ChannelUpdateRequest): Boolean = try {
        val channel = channelMapper.selectById(id)
            ?: throw RuntimeException("Channel not found")

        request.name?.let { channel.name = it }
        request.type?.let { channel.type = it }
        request.agentId?.let { channel.agentId = it }
        request.webhookUrl?.let { channel.webhookUrl = it }
        request.token?.let { channel.token = it }
        request.encodingAesKey?.let { channel.encodingAesKey = it }
        request.appId?.let { channel.appId = it }
        request.appSecret?.let { channel.appSecret = it }
        request.description?.let { channel.description = it }

        channel.updateTime = LocalDateTime.now()
        channelMapper.updateById(channel)
        true
    } catch (e: Exception) {
        log.error("Failed to update Channel", e)
        throw RuntimeException("Failed to update Channel: ${e.message}")
    }

    override fun toggleChannelStatus(id: Long, status: Int): Boolean {
        val channel = channelMapper.selectById(id)
            ?: throw RuntimeException("Channel not found")
        return channelMapper.updateStatus(id, status) > 0
    }

    override fun deleteChannel(id: Long): Boolean = channelMapper.deleteById(id) > 0

    override fun getByCallbackKey(callbackKey: String): Channel? = channelMapper.selectByCallbackKey(callbackKey)

    override fun convertToResponse(channel: Channel): ChannelResponse {
        val response = ChannelResponse.fromEntity(channel)

        // Query agent name
        channel.agentId.let { agentId ->
            val agent = agentService.getAgent(agentId)
            agent?.let {
                response.agentName = it.name
            }
        }

        // Generate callback URL
        channel.callbackKey.let { callbackKey ->
            response.callbackUrl = "$baseUrl/api/channel/callback/$callbackKey"
        }

        return response
    }

    /**
     * Generate unique callback key
     */
    private fun generateCallbackKey(type: String?): String {
        val prefix = type?.lowercase() ?: "ch"
        val uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        return "$prefix-$uuid"
    }
}
