package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.dto.ChannelCreateRequest
import com.vipamp.vipclaw.admin.dto.ChannelResponse
import com.vipamp.vipclaw.admin.dto.ChannelUpdateRequest
import com.vipamp.vipclaw.admin.entity.Agent
import com.vipamp.vipclaw.admin.entity.Channel
import com.vipamp.vipclaw.admin.mapper.ChannelMapper
import com.vipamp.vipclaw.admin.service.AgentService
import com.vipamp.vipclaw.admin.service.ChannelService
import com.vipamp.vipclaw.common.page.Page
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.*

/**
 * Channel 服务实现类
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Service
class ChannelServiceImpl(
    private val channelMapper: ChannelMapper,
    private val agentService: AgentService
) : ChannelService {

    private val log = LoggerFactory.getLogger(ChannelServiceImpl::class.java)

    @Value("\${app.base-url:http://localhost:8080}")
    private lateinit var baseUrl: String

    override fun page(
        keyword: String?,
        type: String?,
        status: Int?,
        pageNum: Int,
        pageSize: Int
    ): Page<Channel> {
        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(channelMapper.selectChannelList(keyword, type, status))
    }

    override fun getChannel(id: Long): Channel? {
        return channelMapper.selectById(id)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createChannel(request: ChannelCreateRequest): Boolean {
        return try {
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

            // 生成唯一的回调标识
            val callbackKey = generateCallbackKey(request.type)
            channel.callbackKey = callbackKey

            channel.createTime = LocalDateTime.now()
            channel.updateTime = LocalDateTime.now()
            channelMapper.insert(channel)
            true
        } catch (e: Exception) {
            log.error("创建 Channel 失败", e)
            throw RuntimeException("创建 Channel 失败：${e.message}")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateChannel(id: Long, request: ChannelUpdateRequest): Boolean {
        return try {
            val channel = channelMapper.selectById(id)
                ?: throw RuntimeException("Channel 不存在")

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
            log.error("更新 Channel 失败", e)
            throw RuntimeException("更新 Channel 失败：${e.message}")
        }
    }

    override fun toggleChannelStatus(id: Long, status: Int): Boolean {
        val channel = channelMapper.selectById(id)
            ?: throw RuntimeException("Channel 不存在")
        return channelMapper.updateStatus(id, status) > 0
    }

    override fun deleteChannel(id: Long): Boolean {
        return channelMapper.deleteById(id) > 0
    }

    override fun getByCallbackKey(callbackKey: String): Channel? {
        return channelMapper.selectByCallbackKey(callbackKey)
    }

    override fun convertToResponse(channel: Channel): ChannelResponse {
        val response = ChannelResponse.fromEntity(channel)

        // 查询智能体名称
        channel.agentId.let { agentId ->
            val agent = agentService.getAgent(agentId)
            agent?.let {
                response.agentName = it.name
            }
        }

        // 生成回调 URL
        channel.callbackKey.let { callbackKey ->
            response.callbackUrl = "$baseUrl/api/channel/callback/$callbackKey"
        }

        return response
    }

    /**
     * 生成唯一的回调标识
     */
    private fun generateCallbackKey(type: String?): String {
        val prefix = type?.lowercase() ?: "ch"
        val uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        return "$prefix-$uuid"
    }
}
