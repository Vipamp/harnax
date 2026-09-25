package com.agnetix.harnax.admin.i18n

import org.springframework.context.MessageSource
import org.springframework.context.support.ReloadableResourceBundleMessageSource

/**
 * The production error bundle, loaded the way `I18nConfig.errorMessageSource` loads it.
 *
 * A test that stubs [MessageUtil] cannot catch a refusal whose key nobody shipped: the bundle answers an
 * unknown code with the code itself (`useCodeAsDefaultMessage`), and an echoing stub hands that straight
 * back, so both halves of the mistake stay invisible and the user ends up reading
 * `error.session.runtime.release` in a failure dialog. Anything asserting on localized failure copy runs on
 * this instead, which is also the only way the three shipped `.properties` files get checked together.
 */
object ErrorBundle {

    fun messageSource(): MessageSource = ReloadableResourceBundleMessageSource().apply {
        setBasename("classpath:i18n/messages_error")
        setDefaultEncoding("UTF-8")
        setCacheSeconds(3600)
        setUseCodeAsDefaultMessage(true)
        setFallbackToSystemLocale(false)
    }

    fun messageUtil(): MessageUtil = MessageUtil(messageSource())
}
