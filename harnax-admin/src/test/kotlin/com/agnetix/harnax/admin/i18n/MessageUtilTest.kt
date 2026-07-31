package com.agnetix.harnax.admin.i18n

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.Locale

class MessageUtilTest {

    private lateinit var messageSource: ResourceBundleMessageSource
    private lateinit var messageUtil: MessageUtil

    @BeforeEach
    fun setUp() {
        messageSource = ResourceBundleMessageSource().apply {
            setBasenames("i18n/messages", "i18n/messages_error")
            setDefaultEncoding("UTF-8")
            setUseCodeAsDefaultMessage(true)
            setFallbackToSystemLocale(false)
        }
        messageUtil = MessageUtil(messageSource)
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
        LocaleContextHolder.resetLocaleContext()
    }

    private fun setAcceptLanguage(header: String) {
        val request = MockHttpServletRequest().apply {
            addHeader("Accept-Language", header)
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    @Test
    fun `getMessage should return Chinese message`() {
        setAcceptLanguage("zh-CN")
        val message = messageUtil.getMessage("error.user.notfound")
        assertEquals("用户不存在", message)
    }

    @Test
    fun `getMessage should return English message`() {
        setAcceptLanguage("en")
        val message = messageUtil.getMessage("error.user.notfound")
        assertEquals("User not found", message)
    }

    @Test
    fun `getMessage should support parameters`() {
        setAcceptLanguage("zh-CN")
        val message = messageUtil.getMessage("error.validation.required", "用户名")
        assertEquals("用户名不能为空", message)
    }

    @Test
    fun `getMessage should return code when not found`() {
        setAcceptLanguage("en")
        val message = messageUtil.getMessage("non.existent.code")
        assertEquals("non.existent.code", message)
    }

    @Test
    fun `getMessageOrDefault should return default when not found`() {
        setAcceptLanguage("en")
        val message = messageUtil.getMessageOrDefault("non.existent.code", "Default message")
        assertEquals("Default message", message)
    }

    @Test
    fun `getMessage should default to English when no request context`() {
        RequestContextHolder.resetRequestAttributes()
        val message = messageUtil.getMessage("error.user.notfound")
        assertEquals("User not found", message)
    }

    @Nested
    @DisplayName("Multi-locale Tests")
    inner class MultiLocaleTests {

        @Test
        @DisplayName("LocaleContextHolder zh_CN - same key resolves to Chinese message")
        fun `same key should return Chinese message when LocaleContextHolder is zh_CN`() {
            // Given
            LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE)

            // When - resolve through the message source with the locale held in LocaleContextHolder
            val message = messageSource.getMessage("error.user.notfound", emptyArray(), LocaleContextHolder.getLocale())

            // Then
            assertEquals("用户不存在", message)
        }

        @Test
        @DisplayName("LocaleContextHolder en_US - same key resolves to English message")
        fun `same key should return English message when LocaleContextHolder is en_US`() {
            // Given
            LocaleContextHolder.setLocale(Locale.US)

            // When
            val message = messageSource.getMessage("error.user.notfound", emptyArray(), LocaleContextHolder.getLocale())

            // Then
            assertEquals("User not found", message)
        }

        @Test
        @DisplayName("LocaleContextHolder switch - same key flips between languages")
        fun `switching LocaleContextHolder should flip the message language for the same key`() {
            // Given / When / Then - zh_CN first
            LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE)
            assertEquals(
                "验证码错误，请重新输入",
                messageSource.getMessage("error.captcha.invalid", emptyArray(), LocaleContextHolder.getLocale()),
            )

            // Switch to en_US, the very same key now yields English
            LocaleContextHolder.setLocale(Locale.US)
            assertEquals(
                "Invalid captcha, please re-enter",
                messageSource.getMessage("error.captcha.invalid", emptyArray(), LocaleContextHolder.getLocale()),
            )
        }

        @Test
        @DisplayName("MessageUtil is header-driven - Accept-Language wins over LocaleContextHolder")
        fun `messageUtil should follow accept-language header regardless of LocaleContextHolder`() {
            // Given - MessageUtil resolves locale from the Accept-Language header, not LocaleContextHolder;
            // set them to conflicting values to pin down that contract
            LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE)
            setAcceptLanguage("en-US")

            // When
            val message = messageUtil.getMessage("error.user.notfound")

            // Then - header (English) wins
            assertEquals("User not found", message)
        }

        @Test
        @DisplayName("Missing key - getMessage falls back to code in every locale")
        fun `missing key should fall back to code itself in both locales`() {
            // Given
            LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE)

            // When & Then - zh_CN
            assertEquals(
                "non.existent.key",
                messageSource.getMessage("non.existent.key", emptyArray(), LocaleContextHolder.getLocale()),
            )

            // When & Then - en_US
            LocaleContextHolder.setLocale(Locale.US)
            assertEquals(
                "non.existent.key",
                messageSource.getMessage("non.existent.key", emptyArray(), LocaleContextHolder.getLocale()),
            )

            // MessageUtil exposes the same fallback through its API
            setAcceptLanguage("zh-CN")
            assertEquals("non.existent.key", messageUtil.getMessage("non.existent.key"))
        }

        @Test
        @DisplayName("Missing key - getMessageOrDefault returns default in each locale")
        fun `missing key should return default message via getMessageOrDefault in both locales`() {
            // Given / When / Then - zh_CN header
            setAcceptLanguage("zh-CN")
            assertEquals("默认消息", messageUtil.getMessageOrDefault("non.existent.key", "默认消息"))

            // en_US header
            setAcceptLanguage("en-US")
            assertEquals("Default message", messageUtil.getMessageOrDefault("non.existent.key", "Default message"))
        }

        @Test
        @DisplayName("Placeholder formatting - {0} substituted per locale")
        fun `parameterized message should format placeholder in both locales`() {
            // Given / When / Then - zh_CN: {0}不能为空
            LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE)
            assertEquals(
                "用户名不能为空",
                messageSource.getMessage("error.validation.required", arrayOf("用户名"), LocaleContextHolder.getLocale()),
            )

            // en_US: {0} is required
            LocaleContextHolder.setLocale(Locale.US)
            assertEquals(
                "username is required",
                messageSource.getMessage("error.validation.required", arrayOf("username"), LocaleContextHolder.getLocale()),
            )

            // Through MessageUtil with the en header
            setAcceptLanguage("en-US")
            assertEquals("email is required", messageUtil.getMessage("error.validation.required", "email"))
        }

        @Test
        @DisplayName("Unsupported locale - falls back to default English bundle")
        fun `unsupported locale should fall back to default English`() {
            // Given - French is not provided; fallbackToSystemLocale=false means the base bundle (English) is used
            LocaleContextHolder.setLocale(Locale.FRENCH)

            // When
            val message = messageSource.getMessage("error.user.notfound", emptyArray(), LocaleContextHolder.getLocale())

            // Then
            assertEquals("User not found", message)
        }

        @Test
        @DisplayName("Unsupported Accept-Language header - MessageUtil defaults to English")
        fun `unsupported accept-language header should make messageUtil default to English`() {
            // Given - "fr-FR" is neither zh nor en, resolveLocale falls back to Locale.ENGLISH
            setAcceptLanguage("fr-FR")

            // When
            val message = messageUtil.getMessage("error.user.notfound")

            // Then
            assertEquals("User not found", message)
        }
    }
}
