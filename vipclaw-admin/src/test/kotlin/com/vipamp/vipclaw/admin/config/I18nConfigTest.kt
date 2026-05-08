package com.vipamp.vipclaw.admin.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.MessageSource
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale
import kotlin.test.assertIs

@SpringBootTest
class I18nConfigTest {

    @Autowired
    lateinit var messageSource: MessageSource

    @Autowired
    lateinit var errorMessageSource: MessageSource

    @Autowired
    lateinit var localeResolver: LocaleResolver

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
        val message = messageSource.getMessage("error.user.notfound", arrayOf(), Locale.SIMPLIFIED_CHINESE)
        assertEquals("用户不存在", message)
    }

    @Test
    fun `messageSource should load English message`() {
        val message = messageSource.getMessage("error.user.notfound", arrayOf(), Locale.ENGLISH)
        assertEquals("User not found", message)
    }

    @Test
    fun `messageSource should support parameterized messages`() {
        val message = messageSource.getMessage("error.validation.required", arrayOf("用户名"), Locale.SIMPLIFIED_CHINESE)
        assertEquals("用户名不能为空", message)
    }

    @Test
    fun `messageSource should return code when message not found`() {
        val message = messageSource.getMessage("non.existent.code", arrayOf(), Locale.ENGLISH)
        assertEquals("non.existent.code", message)
    }
}
