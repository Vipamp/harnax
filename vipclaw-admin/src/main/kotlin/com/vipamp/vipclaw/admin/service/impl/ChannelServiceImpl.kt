package com.vipamp.vipclaw.admin.service.impl

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import com.vipamp.vipclaw.admin.dto.ChannelCreateRequest
import com.vipamp.vipclaw.admin.dto.ChannelResponse
import com.vipamp.vipclaw.admin.dto.ChannelUpdateRequest
import com.vipamp.vipclaw.admin.entity.Agent
import com.vipamp.vipclaw.admin.entity.Channel
import com.vipamp.vipclaw.admin.mapper.ChannelMapper
import com.vipamp.vipclaw.admin.service.AgentService
import com.vipamp.vipclaw.admin.service.ChannelService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

/**
 * Channel 服务实现类
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Service
class ChannelServiceImpl(
    private val agentService: AgentService
) : ServiceImpl<ChannelMapper, Channel>(), ChannelService {

    private val log = LoggerFactory.getLogger(ChannelServiceImpl::class.java)

    @Value("\${app.base-url:http://localhost:8080}")
    private lateinit var baseUrl: String

    override fun getChannelPage(
        keyword: String?,
        type: String?,
        status: Int?,
        current: Int,
        size: Int
    ): Page<Channel> {
        val wrapper = LambdaQueryWrapper<Channel>()

        if (!keyword.isNullOrEmpty()) {
            wrapper.and { w ->
                w.like(Channel::name, keyword)
                    .or()
                    .like(Channel::description, keyword)
            }
        }

        if (!type.isNullOrEmpty()) {
            wrapper.eq(Channel::type, type)
        }

        status?.let { wrapper.eq(Channel::status, it) }

        // 强制校验 active 字段
        wrapper.eq(Channel::active, 1)
        wrapper.orderByDesc(Channel::createTime)

        return page(Page(current.toLong(), size.toLong()), wrapper)
    }

    override fun getChannelById(id: Long): Channel? {
        return getById(id)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createChannel(request: ChannelCreateRequest): Boolean {
        return try {
            val channel = Channel()
            channel.name = request.name
            channel.type = request.type
            channel.agentId = request.agentId
            channel.webhookUrl = request.webhookUrl
            channel.token = request.token
            channel.encodingAesKey = request.encodingAesKey
            channel.appId = request.appId
            channel.appSecret = request.appSecret
            channel.description = request.description
            channel.status = request.status ?: 1

            // 生成唯一的回调标识
            val callbackKey = generateCallbackKey(request.type)
            channel.callbackKey = callbackKey

            save(channel)
        } catch (e: Exception) {
            log.error("创建 Channel 失败", e)
            throw RuntimeException("创建 Channel 失败：${e.message}")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateChannel(id: Long, request: ChannelUpdateRequest): Boolean {
        return try {
            val channel = getById(id)
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
            request.status?.let { channel.status = it }

            updateById(channel)
        } catch (e: Exception) {
            log.error("更新 Channel 失败", e)
            throw RuntimeException("更新 Channel 失败：${e.message}")
        }
    }

    override fun toggleChannelStatus(id: Long, status: Int): Boolean {
        val channel = getById(id)
            ?: throw RuntimeException("Channel 不存在")
        channel.status = status
        return updateById(channel)
    }

    override fun deleteChannel(id: Long): Boolean {
        return removeById(id)
    }

    override fun getByCallbackKey(callbackKey: String): Channel? {
        val wrapper = LambdaQueryWrapper<Channel>()
        wrapper.eq(Channel::callbackKey, callbackKey)
            .eq(Channel::active, 1)
        return getOne(wrapper)
    }

    override fun convertToResponse(channel: Channel): ChannelResponse? {
        if (channel == null) {
            return null
        }

        val response = ChannelResponse.fromEntity(channel)

        // 查询智能体名称
        channel.agentId?.let { agentId ->
            val agent = agentService.getById(agentId)
            agent?.let {
                response.agentName = it.name
            }
        }

        // 生成回调 URL
        channel.callbackKey?.let { callbackKey ->
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
