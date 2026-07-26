package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.ChannelCreateRequest
import com.agnetix.harnax.admin.dto.ChannelResponse
import com.agnetix.harnax.admin.dto.ChannelUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.ChannelService
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.mapper.ChannelMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

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
        val channel = Channel().apply {
            name = request.name!!
            type = request.type!!
            agentId = request.agentId!!
            communicationMode = if (request.type == "wechat") "long_polling" else (request.communicationMode ?: "webhook")
            permissionMode = request.permissionMode ?: "DEFAULT"
            enabled = request.enabled ?: 1
            configJson = request.configJson
            description = request.description
            status = request.status ?: 1
            tenantId = TenantContext.getTenantId() ?: 1
            callbackKey = generateCallbackKey(request.type)
            sessionId = generateSessionId()
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
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
        request.communicationMode?.let { channel.communicationMode = it }
        // Personal WeChat only supports long-polling mode; force-correct it to prevent misconfiguration
        if (channel.type == "wechat") {
            channel.communicationMode = "long_polling"
        }
        request.permissionMode?.let { channel.permissionMode = it }
        request.enabled?.let { channel.enabled = it }
        request.configJson?.let { channel.configJson = it }
        request.description?.let { channel.description = it }
        request.status?.let { channel.status = it }

        channel.updateTime = LocalDateTime.now()
        channelMapper.updateById(channel)
        true
    } catch (e: Exception) {
        log.error("Failed to update Channel", e)
        throw RuntimeException("Failed to update Channel: ${e.message}")
    }

    override fun toggleChannelStatus(id: Long, status: Int): Boolean {
        channelMapper.selectById(id)
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

        // Only webhook mode receives messages via platform push, so the callback URL
        // is meaningful only for webhook channels. websocket / long_polling channels
        // actively pull messages and have no callback endpoint, so we don't expose it.
        if (channel.communicationMode == "webhook") {
            response.callbackUrl = "$baseUrl/api/channel/callback/${channel.callbackKey}"
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

    /**
     * Generate immutable session ID (UUID)
     */
    private fun generateSessionId(): String = "chn-${UUID.randomUUID()}"
}
