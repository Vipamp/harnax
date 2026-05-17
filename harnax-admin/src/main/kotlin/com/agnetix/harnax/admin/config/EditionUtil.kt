package com.agnetix.harnax.admin.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Edition utility
 * Used to check current packaged edition and feature toggles in code
 */
@Component
class EditionUtil {

    private val log = LoggerFactory.getLogger(EditionUtil::class.java)

    companion object {
        const val EDITION_PERSONAL = "personal"
        const val EDITION_ENTERPRISE = "enterprise"
        const val EDITION_PUBLIC = "public"
    }

    @Value("\${edition.current}")
    private lateinit var currentEdition: String

    // Feature toggle configuration
    @Value("\${harnax.features.user-management:false}")
    private var userManagementEnabled: Boolean = false

    @Value("\${harnax.features.token-monitor:false}")
    private var tokenMonitorEnabled: Boolean = false

    @Value("\${harnax.features.billing:false}")
    private var billingEnabled: Boolean = false

    @Value("\${harnax.features.multi-tenant:false}")
    private var multiTenantEnabled: Boolean = false

    @Value("\${harnax.features.agent-sharing:false}")
    private var agentSharingEnabled: Boolean = false

    @Value("\${harnax.features.phone-login:false}")
    private var phoneLoginEnabled: Boolean = false

    @Value("\${harnax.features.email-login:false}")
    private var emailLoginEnabled: Boolean = false

    /**
     * Check if it's personal edition
     */
    fun isPersonal(): Boolean = currentEdition == EDITION_PERSONAL

    /**
     * Check if it's enterprise edition
     */
    fun isEnterprise(): Boolean = currentEdition == EDITION_ENTERPRISE

    /**
     * Check if it's public edition
     */
    fun isPublic(): Boolean = currentEdition == EDITION_PUBLIC

    /**
     * Get current edition name (for debug logging, not exposed externally)
     */
    fun getCurrentEdition(): String = currentEdition

    /**
     * Check if feature is enabled
     * @param feature Feature name
     * @return Whether enabled
     */
    fun isFeatureEnabled(feature: String): Boolean = when (feature) {
        "user-management" -> userManagementEnabled
        "token-monitor" -> tokenMonitorEnabled
        "billing" -> billingEnabled
        "multi-tenant" -> multiTenantEnabled
        "agent-sharing" -> agentSharingEnabled
        "phone-login" -> phoneLoginEnabled
        "email-login" -> emailLoginEnabled
        else -> {
            log.warn("Unknown feature: $feature")
            false
        }
    }
}
