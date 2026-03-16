package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 验证码响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "验证码响应对象")
public class CaptchaResponse {

    @Schema(description = "验证码图片的 Base64 编码", example = "data:image/png;base64,iVBORw0KG...")
    private String imageBase64;

    @Schema(description = "验证码的 key，用于提交时验证", example = "uuid-xxx-xxx-xxx")
    private String captchaKey;

    @Schema(description = "过期时间（秒）", example = "300")
    private Long expiresIn;
}
