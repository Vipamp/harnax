package com.vipamp.vipclaw.channel.client

/**
 * 平台 HTTP 响应
 * 封装各平台 webhook 调用的响应结果
 */
sealed class PlatformResponse {
    /**
     * 成功响应
     * @param statusCode HTTP 状态码
     * @param body 响应体原文
     * @param platformCode 平台特定的成功码（如 "0"）
     * @param platformMessage 平台特定的成功消息
     */
    data class Success(
        val statusCode: Int,
        val body: String,
        val platformCode: String? = null,
        val platformMessage: String? = null,
    ) : PlatformResponse()

    /**
     * 错误响应
     * @param statusCode HTTP 状态码
     * @param body 响应体原文
     * @param platformCode 平台特定的错误码
     * @param platformMessage 平台特定的错误消息
     * @param exception 原始异常
     */
    data class Error(
        val statusCode: Int,
        val body: String,
        val platformCode: String? = null,
        val platformMessage: String? = null,
        val exception: Throwable? = null,
    ) : PlatformResponse()
}
