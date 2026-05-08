package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 验证码响应 DTO
 */
@Schema(description = "验证码响应对象")
data class CaptchaResponse(
    @Schema(description = "验证码图片的 Base64 编码", example = "data:image/png;base64,iVBORw0KG...")
    val imageBase64: String? = null,

    @Schema(description = "验证码的 key，用于提交时验证", example = "uuid-xxx-xxx-xxx")
    val captchaKey: String? = null,

    @Schema(description = "过期时间（秒）", example = "300")
    val expiresIn: Long? = null,
)
