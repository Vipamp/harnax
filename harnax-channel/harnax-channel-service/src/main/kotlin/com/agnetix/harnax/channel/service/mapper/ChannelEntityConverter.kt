package com.agnetix.harnax.channel.service.mapper

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.entity.Channel
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Converter from the Channel entity to the SDK-layer [ChannelSpec].
 *
 * The database channel table only stores common business fields. Channel-specific
 * configuration (appId/appSecret/encodingAesKey/webhookUrl/token, etc.) is stored
 * uniformly in [Channel.configJson]. On startup this utility deserializes configJson
 * and populates a ChannelSpec to satisfy the contracts expected by the SDK and each
 * channel adaptor.
 */
object ChannelEntityConverter {

    private val log = LoggerFactory.getLogger(ChannelEntityConverter::class.java)

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    fun toSpec(entity: Channel): ChannelSpec {
        val cfg = parseConfig(entity.configJson)

        val channelType = ChannelType.fromCode(entity.type)
            ?: throw IllegalArgumentException("Unsupported channel type: ${entity.type}, channelId=${entity.id}")

        return ChannelSpec.builder()
            .id(entity.id)
            .name(entity.name)
            .type(channelType)
            .agentId(entity.agentId)
            .callbackKey(entity.callbackKey)
            .sessionId(entity.sessionId)
            .communicationMode(entity.communicationMode)
            .status(entity.status)
            .webhookUrl(cfg.string("webhookUrl"))
            .token(cfg.string("token"))
            .encodingAesKey(cfg.string("encodingAesKey"))
            .appId(cfg.string("appId"))
            .appSecret(cfg.string("appSecret"))
            .build()
    }

    private fun parseConfig(json: String?): ConfigMap {
        if (json.isNullOrBlank()) return ConfigMap(emptyMap())
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
            ConfigMap(map)
        } catch (e: Exception) {
            log.warn("Failed to parse channel configJson: {}, error: {}", json, e.message)
            ConfigMap(emptyMap())
        }
    }

    private class ConfigMap(private val map: Map<String, Any?>) {
        fun string(key: String): String? = map[key]?.toString()?.takeIf { it.isNotBlank() }
    }
}
