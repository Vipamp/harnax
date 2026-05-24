package com.agnetix.harnax.channel.sdk.error

import com.agnetix.harnax.channel.sdk.config.ChannelType

/**
 * Channel Module Exception Base Class
 * All Channel-related operation exceptions inherit from this class
 */
sealed class ChannelException(
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Channel Not Found Exception
 */
class ChannelNotFoundException(
    val channelId: Long,
) : ChannelException("Channel $channelId not found")

/**
 * Channel Send Message Exception
 * Carries platform-specific error code and error message
 */
class ChannelSendException(
    val channelType: ChannelType,
    val platformErrorCode: String?,
    override val message: String,
    cause: Throwable? = null,
) : ChannelException(message, cause)

/**
 * Channel Signature Verification Failed Exception
 */
class ChannelSignatureException(
    val channelType: ChannelType,
    val detail: String,
) : ChannelException("Signature verification failed for ${channelType.displayName}: $detail")

/**
 * Channel Configuration Incomplete Exception
 */
class ChannelConfigException(
    val channelId: Long,
    val missingFields: List<String>,
) : ChannelException("Channel $channelId missing required config: ${missingFields.joinToString()}")

/**
 * Channel Request Timeout Exception
 */
class ChannelTimeoutException(
    val channelType: ChannelType,
    val timeoutMs: Long,
) : ChannelException("Request timed out for ${channelType.displayName} after ${timeoutMs}ms")

/**
 * Channel Rate Limit Exception
 */
class ChannelRateLimitException(
    val channelType: ChannelType,
    val retryAfterMs: Long?,
) : ChannelException("Rate limited for ${channelType.displayName}, retry after ${retryAfterMs ?: "unknown"}ms")
