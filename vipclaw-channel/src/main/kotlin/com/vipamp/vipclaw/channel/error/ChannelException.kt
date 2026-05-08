package com.vipamp.vipclaw.channel.error

import com.vipamp.vipclaw.channel.ChannelType

/**
 * Channel 模块异常基类
 * 所有 Channel 相关操作的异常都继承自此类
 */
sealed class ChannelException(
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Channel 未找到异常
 */
class ChannelNotFoundException(
    val channelId: Long,
) : ChannelException("Channel $channelId not found")

/**
 * Channel 发送消息异常
 * 携带平台特定的错误码和错误信息
 */
class ChannelSendException(
    val channelType: ChannelType,
    val platformErrorCode: String?,
    override val message: String,
    cause: Throwable? = null,
) : ChannelException(message, cause)

/**
 * Channel 签名验证失败异常
 */
class ChannelSignatureException(
    val channelType: ChannelType,
    val detail: String,
) : ChannelException("Signature verification failed for ${channelType.displayName}: $detail")

/**
 * Channel 配置不完整异常
 */
class ChannelConfigException(
    val channelId: Long,
    val missingFields: List<String>,
) : ChannelException("Channel $channelId missing required config: ${missingFields.joinToString()}")

/**
 * Channel 请求超时异常
 */
class ChannelTimeoutException(
    val channelType: ChannelType,
    val timeoutMs: Long,
) : ChannelException("Request timed out for ${channelType.displayName} after ${timeoutMs}ms")

/**
 * Channel 频率限制异常
 */
class ChannelRateLimitException(
    val channelType: ChannelType,
    val retryAfterMs: Long?,
) : ChannelException("Rate limited for ${channelType.displayName}, retry after ${retryAfterMs ?: "unknown"}ms")
