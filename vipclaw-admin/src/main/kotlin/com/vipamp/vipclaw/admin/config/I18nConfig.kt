package com.vipamp.vipclaw.admin.config

import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale

/**
 * 国际化配置类
 * 
 * 配置 MessageSource 和 LocaleResolver
 */
@Configuration
class I18nConfig {

    /**
     * 配置消息源
     * 
     * 从 i18n/messages*.properties 加载消息资源
     */
    @Bean
    fun messageSource(): MessageSource {
        val messageSource = ReloadableResourceBundleMessageSource()
        messageSource.setBasename("classpath:i18n/messages")
        messageSource.setDefaultEncoding("UTF-8")
        messageSource.setCacheSeconds(3600) // 缓存 1 小时
        messageSource.setUseCodeAsDefaultMessage(true) // 找不到消息时返回 code 本身
        return messageSource
    }

    /**
     * 配置错误消息源
     * 
     * 从 i18n/messages_error*.properties 加载错误消息资源
     */
    @Bean
    fun errorMessageSource(): MessageSource {
        val messageSource = ReloadableResourceBundleMessageSource()
        messageSource.setBasename("classpath:i18n/messages_error")
        messageSource.setDefaultEncoding("UTF-8")
        messageSource.setCacheSeconds(3600)
        messageSource.setUseCodeAsDefaultMessage(true)
        return messageSource
    }

    /**
     * 配置语言解析器
     * 
     * 从 Accept-Language 请求头解析用户语言偏好
     * 默认使用英文
     */
    @Bean
    fun localeResolver(): LocaleResolver {
        val resolver = AcceptHeaderLocaleResolver()
        resolver.setDefaultLocale(Locale.ENGLISH)
        resolver.setSupportedLocales(listOf(
            Locale.ENGLISH,
            Locale.SIMPLIFIED_CHINESE,
            Locale("zh", "CN")
        ))
        return resolver
    }
}
