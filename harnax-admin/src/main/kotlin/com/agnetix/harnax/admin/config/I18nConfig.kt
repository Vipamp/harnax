package com.agnetix.harnax.admin.config

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale

/**
 * 国际化配置
 */
@Configuration
class I18nConfig {

    /**
     * 消息源配置
     */
    @Bean
    fun messageSource(): MessageSource {
        val messageSource = ReloadableResourceBundleMessageSource()
        messageSource.setBasename("classpath:i18n/messages")
        messageSource.setDefaultEncoding("UTF-8")
        messageSource.setCacheSeconds(3600)
        messageSource.setUseCodeAsDefaultMessage(true)
        messageSource.setFallbackToSystemLocale(false) // 禁止fallback到系统Locale，避免中文系统上英文请求返回中文
        return messageSource
    }

    /**
     * 错误消息源配置
     */
    @Bean
    fun errorMessageSource(): MessageSource {
        val messageSource = ReloadableResourceBundleMessageSource()
        messageSource.setBasename("classpath:i18n/messages_error")
        messageSource.setDefaultEncoding("UTF-8")
        messageSource.setCacheSeconds(3600)
        messageSource.setUseCodeAsDefaultMessage(true)
        messageSource.setFallbackToSystemLocale(false) // 禁止fallback到系统Locale，避免中文系统上英文请求返回中文
        return messageSource
    }

    /**
     * Locale解析器
     * 从 Accept-Language 请求头解析语言偏好，默认英文
     */
    @Bean
    fun localeResolver(): LocaleResolver {
        return object : AcceptHeaderLocaleResolver() {
            override fun resolveLocale(request: HttpServletRequest): Locale {
                val header = request.getHeader("Accept-Language")
                if (header.isNullOrBlank()) {
                    return Locale.ENGLISH
                }
                return when {
                    header.startsWith("zh", ignoreCase = true) -> Locale.SIMPLIFIED_CHINESE
                    header.startsWith("en", ignoreCase = true) -> Locale.ENGLISH
                    else -> Locale.ENGLISH
                }
            }
        }
    }
}
