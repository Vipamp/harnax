package com.agnetix.harnax.admin.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.MessageSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale
import kotlin.test.assertIs

class I18nConfigTest {

    private lateinit var i18nConfig: I18nConfig
    private lateinit var messageSource: MessageSource
    private lateinit var errorMessageSource: MessageSource
    private lateinit var localeResolver: LocaleResolver

    @BeforeEach
    fun setUp() {
        i18nConfig = I18nConfig()
        messageSource = i18nConfig.messageSource()
        errorMessageSource = i18nConfig.errorMessageSource()
        localeResolver = i18nConfig.localeResolver()
    }

    @Test
    fun `messageSource bean should be configured`() {
        assertNotNull(messageSource)
    }

    @Test
    fun `errorMessageSource bean should be configured`() {
        assertNotNull(errorMessageSource)
    }

    @Test
    fun `localeResolver should be AcceptHeaderLocaleResolver`() {
        assertIs<AcceptHeaderLocaleResolver>(localeResolver)
    }

    @Test
    fun `messageSource should load Chinese message`() {
        val message = errorMessageSource.getMessage("error.user.notfound", arrayOf(), Locale.SIMPLIFIED_CHINESE)
        assertEquals("用户不存在", message)
    }

    @Test
    fun `messageSource should load English message`() {
        val message = errorMessageSource.getMessage("error.user.notfound", arrayOf(), Locale.ENGLISH)
        assertEquals("User not found", message)
    }

    @Test
    fun `messageSource should support parameterized messages`() {
        val message = errorMessageSource.getMessage("error.validation.required", arrayOf("用户名"), Locale.SIMPLIFIED_CHINESE)
        assertEquals("用户名不能为空", message)
    }

    @Test
    fun `messageSource should return code when message not found`() {
        val message = errorMessageSource.getMessage("non.existent.code", arrayOf(), Locale.ENGLISH)
        assertEquals("non.existent.code", message)
    }

    @Test
    fun `localeResolver should return Chinese for zh-CN header`() {
        val request = MockHttpServletRequest().apply {
            addHeader("Accept-Language", "zh-CN")
        }
        assertEquals(Locale.SIMPLIFIED_CHINESE, localeResolver.resolveLocale(request))
    }

    @Test
    fun `localeResolver should return English for en header`() {
        val request = MockHttpServletRequest().apply {
            addHeader("Accept-Language", "en-US")
        }
        assertEquals(Locale.ENGLISH, localeResolver.resolveLocale(request))
    }

    @Test
    fun `localeResolver should default to English when no header`() {
        val request = MockHttpServletRequest()
        assertEquals(Locale.ENGLISH, localeResolver.resolveLocale(request))
    }
}
