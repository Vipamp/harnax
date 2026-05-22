package com.agnetix.harnax.admin.i18n

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.Locale

/**
 * 国际化消息工具类
 */
@Component
class MessageUtil(
    @Qualifier("errorMessageSource")
    private val messageSource: MessageSource,
) {
    /**
     * 从 Accept-Language 请求头解析 Locale
     * 支持: en, en-US, zh, zh-CN，默认英文
     */
    private fun resolveLocale(): Locale {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        val header = attributes?.request?.getHeader("Accept-Language")

        if (header.isNullOrBlank()) {
            return Locale.ENGLISH
        }

        return when {
            header.startsWith("zh", ignoreCase = true) -> Locale.SIMPLIFIED_CHINESE
            header.startsWith("en", ignoreCase = true) -> Locale.ENGLISH
            else -> Locale.ENGLISH
        }
    }

    /**
     * 获取国际化消息
     */
    fun getMessage(code: String, vararg args: Any): String {
        val locale = resolveLocale()
        return try {
            messageSource.getMessage(code, args, locale)
        } catch (e: Exception) {
            code
        }
    }

    /**
     * 获取国际化消息（带默认值）
     */
    fun getMessage(code: String, defaultMessage: String, vararg args: Any): String {
        val locale = resolveLocale()
        return try {
            messageSource.getMessage(code, args, locale)
        } catch (e: Exception) {
            defaultMessage
        }
    }
}