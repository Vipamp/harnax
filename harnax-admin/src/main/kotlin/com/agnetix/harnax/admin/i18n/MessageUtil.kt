package com.agnetix.harnax.admin.i18n

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component

/**
 * Internationalization message utility
 *
 * Encapsulate MessageSource calls and provide convenient message retrieval methods
 */
@Component
class MessageUtil(
    @Qualifier("errorMessageSource")
    private val messageSource: MessageSource,
) {
    /**
     * Get internationalized message
     *
     * @param code Message code
     * @param args Message arguments
     * @return Internationalized message text
     */
    fun getMessage(code: String, vararg args: Any): String {
        val locale = LocaleContextHolder.getLocale()
        return try {
            messageSource.getMessage(code, args, locale)
        } catch (e: Exception) {
            // Return code itself if message not found
            code
        }
    }

    /**
     * Get internationalized message (with default value)
     *
     * @param code Message code
     * @param defaultMessage Default message
     * @param args Message arguments
     * @return Internationalized message text
     */
    fun getMessage(code: String, defaultMessage: String, vararg args: Any): String {
        val locale = LocaleContextHolder.getLocale()
        return try {
            messageSource.getMessage(code, args, locale)
        } catch (e: Exception) {
            defaultMessage
        }
    }
}
