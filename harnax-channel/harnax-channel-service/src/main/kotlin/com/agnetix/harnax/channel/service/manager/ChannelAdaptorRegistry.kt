package com.agnetix.harnax.channel.service.manager

import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.config.ChannelType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Registry that resolves a [ChannelAdaptor] by [ChannelType].
 *
 * All ChannelAdaptor beans are injected as a list by Spring and indexed by their
 * declared type. This lets the orchestration layer (ChannelBootstrapRunner,
 * ChannelManager) dispatch strictly by channel type, so adding a new channel
 * requires only registering a new adaptor bean — no changes to the dispatch
 * logic. The communicationMode field is then purely descriptive of the
 * transport (websocket/stream/long_polling/webhook) and no longer used for
 * dispatch.
 */
@Component
class ChannelAdaptorRegistry(adaptors: List<ChannelAdaptor>) {

    private val log = LoggerFactory.getLogger(ChannelAdaptorRegistry::class.java)

    private val byType: Map<ChannelType, ChannelAdaptor> = adaptors.associateBy { it.getType() }

    init {
        log.info("Registered channel adaptors: {}", byType.keys.map { it.code })
    }

    fun get(type: ChannelType): ChannelAdaptor = byType[type] ?: throw IllegalArgumentException("No channel adaptor registered for type: $type")

    fun contains(type: ChannelType): Boolean = byType.containsKey(type)
}
