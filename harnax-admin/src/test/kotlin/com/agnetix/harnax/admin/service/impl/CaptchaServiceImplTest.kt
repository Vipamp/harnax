package com.agnetix.harnax.admin.service.impl

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the captcha test-master-code bypass used by harnax-ui-test.
 * The bypass must be a no-op when the master code is not configured.
 */
class CaptchaServiceImplTest {

    @Test
    fun `master code disabled by default - any code fails for unknown key`() {
        val service = CaptchaServiceImpl(testMasterCode = "")
        assertFalse(service.validateCaptcha("no-such-key", "TEST"))
        assertFalse(service.validateCaptcha("no-such-key", ""))
    }

    @Test
    fun `master code enabled - matching code passes without a stored captcha`() {
        val service = CaptchaServiceImpl(testMasterCode = "UI_TEST_2026")
        assertTrue(service.validateCaptcha("no-such-key", "UI_TEST_2026"))
    }

    @Test
    fun `master code enabled - non-matching code still validated normally`() {
        val service = CaptchaServiceImpl(testMasterCode = "UI_TEST_2026")
        assertFalse(service.validateCaptcha("no-such-key", "WRONG"))
    }

    @Test
    fun `master code is case sensitive and does not consume stored captchas`() {
        val service = CaptchaServiceImpl(testMasterCode = "UI_TEST_2026")
        assertFalse(service.validateCaptcha("no-such-key", "ui_test_2026"))

        // A real captcha generated before the bypass is still usable afterwards
        val generated = service.generateCaptcha()
        assertTrue(service.validateCaptcha(generated.captchaKey, "UI_TEST_2026"))
        // Stored captcha not consumed by the master-code path: wrong code now fails normally
        assertFalse(service.validateCaptcha(generated.captchaKey, "XXXX"))
    }

    @Test
    fun `normal captcha flow unaffected - one time use and case insensitive`() {
        val service = CaptchaServiceImpl(testMasterCode = "")
        val generated = service.generateCaptcha()

        // Read the plain code from the in-memory store via reflection
        val storeField = CaptchaServiceImpl::class.java.getDeclaredField("captchaStore")
        storeField.isAccessible = true
        val store = storeField.get(service) as java.util.concurrent.ConcurrentHashMap<*, *>
        val info = store[generated.captchaKey]!!
        val codeField = info.javaClass.getDeclaredField("code")
        codeField.isAccessible = true
        val code = codeField.get(info) as String

        assertTrue(service.validateCaptcha(generated.captchaKey, code.lowercase()))
        assertFalse(service.validateCaptcha(generated.captchaKey, code), "captcha must be one-time use")
    }
}
