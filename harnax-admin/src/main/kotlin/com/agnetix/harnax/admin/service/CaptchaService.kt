package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.CaptchaResponse

/**
 * Captcha service interface
 */
interface CaptchaService {
    /**
     * Generate captcha
     *
     * @return Captcha response, contains Base64 image and key
     */
    fun generateCaptcha(): CaptchaResponse

    /**
     * Validate captcha
     *
     * @param captchaKey Captcha key
     * @param code       User input captcha code
     * @return Validation result true/false
     */
    fun validateCaptcha(captchaKey: String, code: String): Boolean
}
