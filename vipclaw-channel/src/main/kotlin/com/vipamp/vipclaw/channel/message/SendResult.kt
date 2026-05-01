package com.vipamp.vipclaw.channel.message

/**
 * 消息发送结果
 */
sealed class SendResult {
    /**
     * 发送成功
     * @param messageId 平台返回的消息 ID
     * @param timestamp 发送时间戳
     */
    data class Success(
        val messageId: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : SendResult()

    /**
     * 发送失败
     * @param errorCode 错误码
     * @param errorMessage 错误消息
     * @param cause 原始异常
     */
    data class Failure(
        val errorCode: String,
        val errorMessage: String,
        val cause: Throwable? = null
    ) : SendResult()
}
