package com.agnetix.harnax.admin.i18n

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

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
}
