package com.agnetix.harnax.channel.feishu.client

/**
 * Platform HTTP Response
 * Encapsulates response results from platform webhook calls
 */
sealed class PlatformResponse {
    /**
     * Success response
     * @param statusCode HTTP status code
     * @param body Response body text
     * @param platformCode Platform-specific success code (e.g., "0")
     * @param platformMessage Platform-specific success message
     */
    data class Success(
        val statusCode: Int,
        val body: String,
        val platformCode: String? = null,
        val platformMessage: String? = null,
    ) : PlatformResponse()

    /**
     * Error response
     * @param statusCode HTTP status code
     * @param body Response body text
     * @param platformCode Platform-specific error code
     * @param platformMessage Platform-specific error message
     * @param exception Original exception
     */
    data class Error(
        val statusCode: Int,
        val body: String,
        val platformCode: String? = null,
        val platformMessage: String? = null,
        val exception: Throwable? = null,
    ) : PlatformResponse()
}
