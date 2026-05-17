package com.agnetix.harnax.admin.config

import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale

/**
 * Internationalization configuration
 *
 * Configure MessageSource and LocaleResolver
 */
@Configuration
class I18nConfig {

    /**
     * Configure message source
     *
     * Load message resources from i18n/messages*.properties
     */
    @Bean
    fun messageSource(): MessageSource {
        val messageSource = ReloadableResourceBundleMessageSource()
        messageSource.setBasename("classpath:i18n/messages")
        messageSource.setDefaultEncoding("UTF-8")
        messageSource.setCacheSeconds(3600) // Cache for 1 hour
        messageSource.setUseCodeAsDefaultMessage(true) // Return code itself when message not found
        return messageSource
    }

    /**
     * Configure error message source
     *
     * Load error message resources from i18n/messages_error*.properties
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
     * Configure locale resolver
     *
     * Parse user language preference from Accept-Language request header
     * Default to English
     */
    @Bean
    fun localeResolver(): LocaleResolver {
        val resolver = AcceptHeaderLocaleResolver()
        resolver.setDefaultLocale(Locale.ENGLISH)
        resolver.setSupportedLocales(
            listOf(
                Locale.ENGLISH,
                Locale.SIMPLIFIED_CHINESE,
                Locale("zh", "CN"),
            ),
        )
        return resolver
    }
}
