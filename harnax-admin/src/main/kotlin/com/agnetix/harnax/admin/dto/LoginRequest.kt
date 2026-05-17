package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Login request DTO
 */
@Schema(description = "Login request object")
data class LoginRequest(
    @Schema(description = "Username", example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
    val username: String = "",

    @Schema(description = "Password", example = "admin123", requiredMode = Schema.RequiredMode.REQUIRED)
    val password: String? = null,

    @Schema(description = "Captcha", example = "1234")
    val captcha: String? = null,

    @Schema(description = "Captcha key, returned when getting captcha", example = "uuid-xxx-xxx")
    val captchaKey: String? = null,

    @Schema(description = "Auto login", example = "true")
    val autoLogin: Boolean? = null,
)
