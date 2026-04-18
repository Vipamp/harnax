package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 登录请求 DTO
 */
@Schema(description = "登录请求对象")
data class LoginRequest(
    @Schema(description = "用户名", example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
    val username: String = "",

    @Schema(description = "密码", example = "admin123", requiredMode = Schema.RequiredMode.REQUIRED)
    val password: String? = null,

    @Schema(description = "验证码", example = "1234")
    val captcha: String? = null,

    @Schema(description = "验证码 key，获取验证码时返回", example = "uuid-xxx-xxx")
    val captchaKey: String? = null,

    @Schema(description = "自动登录", example = "true")
    val autoLogin: Boolean? = null
)
