package com.vipamp.vipclaw.admin.service;

import com.vipamp.vipclaw.admin.dto.CaptchaResponse;

/**
 * 验证码服务接口
 */
public interface CaptchaService {

    /**
     * 生成验证码
     *
     * @return 验证码响应，包含 Base64 图片和 key
     */
    CaptchaResponse generateCaptcha();

    /**
     * 验证验证码
     *
     * @param captchaKey 验证码 key
     * @param code       用户输入的验证码
     * @return 验证结果 true/false
     */
    boolean validateCaptcha(String captchaKey, String code);
}
