package com.agnetix.harnax.common.error

/**
 * 通用错误码枚举
 * 定义系统中所有可能的业务错误码
 */
enum class HarnaxErrorCode(
    val code: String,
    val defaultMessage: String,
) {
    // ==================== 通用错误 (1000-1999) ====================
    SUCCESS("1000", "操作成功"),
    SYSTEM_ERROR("1001", "系统内部错误"),
    PARAM_ERROR("1002", "参数错误"),
    INVALID_PARAM("1003", "参数 [{}] 无效"),
    MISSING_PARAM("1004", "缺少必要参数 [{}]"),

    // ==================== 认证授权错误 (2000-2999) ====================
    UNAUTHORIZED("2001", "未授权访问"),
    FORBIDDEN("2002", "禁止访问"),
    TOKEN_EXPIRED("2003", "Token 已过期"),
    TOKEN_INVALID("2004", "Token 无效"),
    LOGIN_FAILED("2005", "登录失败"),
    USER_NOT_FOUND("2006", "用户 [{}] 不存在"),
    PASSWORD_ERROR("2007", "密码错误"),
    USER_DISABLED("2008", "用户已被禁用"),

    // ==================== 数据错误 (3000-3999) ====================
    DATA_NOT_FOUND("3001", "数据不存在"),
    DATA_ALREADY_EXISTS("3002", "数据已存在"),
    DATA_CONFLICT("3003", "数据冲突"),
    DATA_INVALID("3004", "数据格式错误"),

    // ==================== 业务错误 (4000-4999) ====================
    OPERATION_FAILED("4001", "操作失败"),
    OPERATION_NOT_ALLOWED("4002", "不允许的操作"),
    STATUS_ERROR("4003", "状态错误"),
    RESOURCE_NOT_FOUND("4004", "资源 [{}] 不存在"),
    RESOURCE_LOCKED("4005", "资源已被锁定"),

    // ==================== 数据库错误 (5000-5999) ====================
    DB_ERROR("5001", "数据库操作失败"),
    DB_DUPLICATE_KEY("5002", "唯一键冲突"),
    DB_CONSTRAINT_VIOLATION("5003", "约束违反"),
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
