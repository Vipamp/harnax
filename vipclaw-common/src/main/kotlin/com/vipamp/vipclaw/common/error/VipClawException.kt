package com.vipamp.vipclaw.common.error

/**
 * VipClaw 通用异常类
 * 用于统一处理系统中的业务异常
 *
 * @param code 错误码
 * @param message 错误消息
 */
class VipClawException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    companion object {
        /**
         * 格式化创建异常
         * 支持使用占位符，自动替换参数
         *
         * @param errorCode 错误码
         * @param errorMsgTemplate 错误消息模板（支持 {} 占位符）
         * @param args 占位符参数
         * @return VipClawException 实例
         *
         * 示例：
         * throw VipClawException.format("USER_NOT_FOUND", "用户 [{}] 不存在", userId)
         * throw VipClawException.format("INVALID_PARAM", "参数 [{}] 的值 [{}] 无效", paramName, value)
         */
        fun format(errorCode: String, errorMsgTemplate: String, vararg args: Any?): VipClawException {
            val formattedMessage = if (args.isNotEmpty()) {
                var message = errorMsgTemplate
                args.forEach { arg ->
                    message = message.replaceFirst("{}", arg?.toString() ?: "null")
                }
                message
            } else {
                errorMsgTemplate
            }
            return VipClawException(errorCode, formattedMessage)
        }

        /**
         * 创建异常（不带格式化）
         *
         * @param errorCode 错误码
         * @param errorMsg 错误消息
         * @return VipClawException 实例
         */
        fun of(errorCode: String, errorMsg: String): VipClawException = VipClawException(errorCode, errorMsg)

        /**
         * 创建异常（带原始异常）
         *
         * @param errorCode 错误码
         * @param errorMsg 错误消息
         * @param cause 原始异常
         * @return VipClawException 实例
         */
        fun of(errorCode: String, errorMsg: String, cause: Throwable): VipClawException = VipClawException(errorCode, errorMsg, cause)
    }
}
