package com.agnetix.harnax.channel.service.mapper

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.entity.Channel
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory

/**
 * Channel 实体 → SDK 层 [ChannelSpec] 的转换器。
 *
 * 数据库表 channel 仅保留公共业务字段，渠道差异化配置（appId/appSecret/
 * encodingAesKey/webhookUrl/token 等）统一存于 [Channel.configJson]。
 * 启动时通过本工具反序列化 configJson 并填充到 ChannelSpec 中，
 * 以便适配 SDK / 各渠道适配器现有契约。
 */
object ChannelEntityConverter {

    private val log = LoggerFactory.getLogger(ChannelEntityConverter::class.java)

    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

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
