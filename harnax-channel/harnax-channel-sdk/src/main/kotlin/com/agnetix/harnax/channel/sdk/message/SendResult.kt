package com.agnetix.harnax.channel.sdk.message

/**
 * Message Send Result
 */
sealed class SendResult {
    /**
     * Send successful
     * @param messageId Message ID returned by the platform
     * @param timestamp Send timestamp
     */
    data class Success(
        val messageId: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
    ) : SendResult()

    /**
     * Send failed
     * @param errorCode Error code
     * @param errorMessage Error message
     * @param cause Original exception
     */
    data class Failure(
        val errorCode: String,
        val errorMessage: String,
        val cause: Throwable? = null,
    ) : SendResult()
}
