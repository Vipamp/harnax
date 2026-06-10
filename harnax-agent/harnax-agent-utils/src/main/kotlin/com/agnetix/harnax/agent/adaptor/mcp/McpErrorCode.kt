package com.agnetix.harnax.agent.adaptor.mcp

import com.agnetix.harnax.common.error.HarnaxException

/**
 * MCP 模块错误码
 * 定义 MCP 客户端相关的业务错误码
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: harnax
 */
enum class McpErrorCode(
    val code: String,
    val defaultMessage: String,
) {
    /**
     * MCP 客户端创建失败
     */
    MCP_CLIENT_CREATE_FAILED("6001", "MCP 客户端创建失败"),

    /**
     * MCP 连接失败
     */
    MCP_CONNECTION_FAILED("6002", "MCP 连接失败"),

    /**
     * MCP 客户端不存在
     */
    MCP_CLIENT_NOT_FOUND("6003", "MCP 客户端 [{}] 不存在"),
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
