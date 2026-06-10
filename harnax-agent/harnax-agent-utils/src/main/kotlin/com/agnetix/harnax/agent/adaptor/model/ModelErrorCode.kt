package com.agnetix.harnax.agent.adaptor.model

import com.agnetix.harnax.common.error.HarnaxException

/**
 * Model 模块错误码
 * 定义模型相关的业务错误码
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: harnax
 */
enum class ModelErrorCode(
    val code: String,
    val defaultMessage: String,
) {
    /**
     * 模型创建失败
     */
    MODEL_CREATE_FAILED("7001", "模型创建失败"),

    /**
     * 模型配置不存在
     */
    MODEL_CONFIG_NOT_FOUND("7002", "模型配置 [{}] 不存在"),

    /**
     * 不支持的模型类型
     */
    UNSUPPORTED_MODEL_TYPE("7003", "不支持的模型类型: {}"),
    ;

    /**
     * 创建异常（不带格式化参数）
     */
    fun format(): HarnaxException = HarnaxException(this.code, this.defaultMessage)

    /**
     * 创建异常（带格式化参数）
     * @param args 用于替换消息模板中的占位符参数
     */
    fun format(vararg args: Any?): HarnaxException {
        val formattedMessage = if (args.isNotEmpty()) {
            var message = this.defaultMessage
            args.forEach { arg ->
                message = message.replaceFirst("{}", arg?.toString() ?: "null")
            }
            message
        } else {
            this.defaultMessage
        }
        return HarnaxException(this.code, formattedMessage)
    }

    /**
     * 创建异常（带原始异常）
     */
    fun format(cause: Throwable): HarnaxException = HarnaxException(this.code, this.defaultMessage, cause)

    /**
     * 创建异常（带格式化参数和原始异常）
     */
    fun format(cause: Throwable, vararg args: Any?): HarnaxException {
        val formattedMessage = if (args.isNotEmpty()) {
            var message = this.defaultMessage
            args.forEach { arg ->
                message = message.replaceFirst("{}", arg?.toString() ?: "null")
            }
            message
        } else {
            this.defaultMessage
        }
        return HarnaxException(this.code, formattedMessage, cause)
    }
}
