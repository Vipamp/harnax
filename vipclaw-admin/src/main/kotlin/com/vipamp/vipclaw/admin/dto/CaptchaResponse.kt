package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Captcha response DTO
 */
@Schema(description = "Captcha response object")
data class CaptchaResponse(
    @Schema(description = "Base64 encoded captcha image", example = "data:image/png;base64,iVBORw0KG...")
    val imageBase64: String? = null,

    @Schema(description = "Captcha key for verification submission", example = "uuid-xxx-xxx-xxx")
    val captchaKey: String? = null,

    @Schema(description = "Expiration time in seconds", example = "300")
    val expiresIn: Long? = null,
)
