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
import tools.jackson.module.kotlin.jacksonObjectMapper
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

    private val objectMapper = jacksonObjectMapper()

    @Value("\${app.base-url:http://localhost:8080}")
    private lateinit var baseUrl: String

    override fun page(
        keyword: String?,
        type: String?,
        status: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<Channel> {
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<Agent>(safePageNum, safePageSize)
        return Page.fromPageInfo(channelMapper.selectChannelList(keyword, type, status))
    }

    override fun getChannel(id: Long): Channel? = channelMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createChannel(request: ChannelCreateRequest): Boolean = try {
        validateConfigJson(request.configJson)
        val channel = Channel().apply {
            name = request.name!!
            type = request.type!!
            agentId = request.agentId!!
            communicationMode = resolveCommunicationMode(request.type, request.communicationMode)
            permissionMode = request.permissionMode ?: "DEFAULT"
            enabled = request.enabled ?: 1
            configJson = request.configJson
            description = request.description
            status = requireValidStatus(request.status ?: 1)
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
        request.agentId?.let { channel.agentId = it }
        val typeChanged = request.type != null && request.type != channel.type
        request.type?.let { channel.type = it }
        request.communicationMode?.let { channel.communicationMode = it }
        // Re-resolve on an explicit mode *or* a type change: otherwise the previous type's mode
        // survives (feishu/webhook edited into dingtalk still claims webhook) and the channel ends
        // up in exactly the healthy-but-deaf state the mode validation exists to prevent.
        if (typeChanged || request.communicationMode != null) {
            channel.communicationMode = resolveCommunicationMode(channel.type, channel.communicationMode)
        }
        // Personal WeChat only supports long-polling mode; force-correct it to prevent misconfiguration
        if (channel.type == "wechat") {
            channel.communicationMode = "long_polling"
        }
        request.permissionMode?.let { channel.permissionMode = it }
        request.enabled?.let { channel.enabled = it }
        request.configJson?.let {
            validateConfigJson(it)
            channel.configJson = it
        }
        request.description?.let { channel.description = it }
        request.status?.let { channel.status = requireValidStatus(it) }

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
        // Anything but 0/1 here would store a value the runtime's `status = 1` filter can never
        // match and the list cannot even filter by.
        return channelMapper.updateStatus(id, requireValidStatus(status)) > 0
    }

    private fun requireValidStatus(status: Int): Int {
        if (status != 0 && status != 1) {
            throw RuntimeException("Channel status must be 0 (disabled) or 1 (enabled), got $status")
        }
        return status
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
     * Validate and default the communication mode for a channel.
     *
     * The runtime is the authority and it only speaks up on its next reconcile tick: it maps the
     * type with the case-sensitive `ChannelType.fromCode`, then picks a transport by mode. Any
     * combination nothing implements produces a row that looks healthy and never receives a
     * message, so rejecting here turns that into an error the caller can act on.
     *
     * On update this runs only when the request names a mode or changes the type, so a legacy row
     * can still be renamed — but a type change never leaves the previous type's mode behind.
     */
    private fun resolveCommunicationMode(
        type: String?,
        requested: String?,
    ): String {
        val allowed = MODES_BY_TYPE[type]
            ?: throw RuntimeException("Unsupported channel type '$type'; expected one of ${MODES_BY_TYPE.keys}")
        val mode = requested?.takeIf { it.isNotBlank() } ?: allowed.first()
        if (mode !in allowed) {
            throw RuntimeException("Channel type '$type' cannot run in mode '$mode'; allowed modes are $allowed")
        }
        return mode
    }

    /**
     * Reject a `configJson` that is not a JSON object, and one that cannot fit the column.
     *
     * The runtime reads this blob for credentials and silently falls back to an empty config when
     * it will not parse, so an unbalanced brace costs an operator a deaf channel that reports no
     * error anywhere.
     */
    private fun validateConfigJson(configJson: String?) {
        if (configJson.isNullOrBlank()) {
            return
        }
        if (configJson.length > MAX_CONFIG_JSON_CHARS) {
            throw RuntimeException("Channel config exceeds the $MAX_CONFIG_JSON_CHARS character limit")
        }
        val tree = try {
            objectMapper.readTree(configJson)
        } catch (e: Exception) {
            // The payload is deliberately not echoed: it holds app secrets.
            throw RuntimeException("Channel config must be a JSON object")
        }
        if (!tree.isObject) {
            throw RuntimeException("Channel config must be a JSON object")
        }
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

    private companion object {
        const val WEBHOOK_MODE = "webhook"

        /**
         * Modes each channel type can actually run, first entry being the default when the caller
         * names none. Keep in step with `CHANNEL_MODES` in the WebUI and the transports in
         * harnax-channel: three copies of a fact is unfortunate, but they sit in three languages
         * with no shared module and only the runtime can check it at read time.
         *
         * Case-sensitive on purpose — the runtime resolves the code with `ChannelType.fromCode`,
         * which matches exactly, so `"DingTalk"` would persist here and fail to load there.
         *
         * `http` is listed although no adaptor is registered for it yet: it stays a valid value the
         * runtime reports as `no adaptor registered`, rather than being deleted from the product by
         * a validation change.
         */
        val MODES_BY_TYPE = mapOf(
            "feishu" to setOf("websocket", WEBHOOK_MODE),
            "dingtalk" to setOf("stream"),
            "wecom" to setOf("websocket"),
            "wechat" to setOf("long_polling"),
            "http" to setOf(WEBHOOK_MODE),
        )

        /**
         * Well under the `TEXT` column's 65 535 bytes: this blob only carries credentials and
         * platform passthrough, and a multi-byte name would push a character-legal value past a
         * byte-legal one.
         */
        const val MAX_CONFIG_JSON_CHARS = 20_000
    }
}
