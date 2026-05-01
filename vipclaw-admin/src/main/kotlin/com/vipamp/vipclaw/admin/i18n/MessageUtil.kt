package com.vipamp.vipclaw.admin.i18n

import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component

/**
 * 国际化消息工具类
 * 
 * 封装 MessageSource 的调用，提供便捷的消息获取方法
 */
@Component
class MessageUtil(
    private val messageSource: MessageSource
) {
    /**
     * 获取国际化消息
     * 
     * @param code 消息代码
     * @param args 消息参数
     * @return 国际化消息文本
     */
    fun getMessage(code: String, vararg args: Any): String {
        val locale = LocaleContextHolder.getLocale()
        return try {
            messageSource.getMessage(code, args, locale)
        } catch (e: Exception) {
            // 如果找不到消息，返回 code 本身
            code
        }
    }

    /**
     * 获取国际化消息（带默认值）
     * 
     * @param code 消息代码
     * @param defaultMessage 默认消息
     * @param args 消息参数
     * @return 国际化消息文本
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
