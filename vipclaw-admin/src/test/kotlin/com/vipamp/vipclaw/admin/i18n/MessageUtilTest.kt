package com.vipamp.vipclaw.admin.i18n

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.util.Locale

@SpringBootTest
@TestPropertySource(properties = [
    "spring.messages.basename=i18n/messages,i18n/messages_error",
    "spring.messages.encoding=UTF-8"
])
class MessageUtilTest {

    @Autowired
    lateinit var messageUtil: MessageUtil

    @Test
    fun `getMessage should return Chinese message`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val message = messageUtil.getMessage("error.user.notfound")
        assertEquals("用户不存在", message)
    }

    @Test
    fun `getMessage should return English message`() {
        Locale.setDefault(Locale.ENGLISH)
        val message = messageUtil.getMessage("error.user.notfound")
        assertEquals("User not found", message)
    }

    @Test
    fun `getMessage should support parameters`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val message = messageUtil.getMessage("error.validation.required", "用户名")
        assertEquals("用户名不能为空", message)
    }

    @Test
    fun `getMessage should return code when not found`() {
        Locale.setDefault(Locale.ENGLISH)
        val message = messageUtil.getMessage("non.existent.code")
        assertEquals("non.existent.code", message)
    }

    @Test
    fun `getMessage with default should return default when not found`() {
        Locale.setDefault(Locale.ENGLISH)
        val message = messageUtil.getMessage("non.existent.code", "Default message")
        assertEquals("Default message", message)
    }
}
