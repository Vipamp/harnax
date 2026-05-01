package com.vipamp.vipclaw.admin.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 版本工具类
 * 用于在代码中判断当前打包的版本和功能开关
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
    
    // 功能开关配置
    @Value("\${vipclaw.features.user-management:false}")
    private var userManagementEnabled: Boolean = false
    
    @Value("\${vipclaw.features.token-monitor:false}")
    private var tokenMonitorEnabled: Boolean = false
    
    @Value("\${vipclaw.features.billing:false}")
    private var billingEnabled: Boolean = false
    
    @Value("\${vipclaw.features.multi-tenant:false}")
    private var multiTenantEnabled: Boolean = false
    
    @Value("\${vipclaw.features.agent-sharing:false}")
    private var agentSharingEnabled: Boolean = false
    
    @Value("\${vipclaw.features.phone-login:false}")
    private var phoneLoginEnabled: Boolean = false
    
    @Value("\${vipclaw.features.email-login:false}")
    private var emailLoginEnabled: Boolean = false
    
    /**
     * 判断是否是个人版
     */
    fun isPersonal(): Boolean = currentEdition == EDITION_PERSONAL
    
    /**
     * 判断是否是企业版
     */
    fun isEnterprise(): Boolean = currentEdition == EDITION_ENTERPRISE
    
    /**
     * 判断是否是公网版
     */
    fun isPublic(): Boolean = currentEdition == EDITION_PUBLIC
    
    /**
     * 获取当前版本名称(用于调试日志,不对外暴露)
     */
    fun getCurrentEdition(): String = currentEdition
    
    /**
     * 检查功能是否启用
     * @param feature 功能名称
     * @return 是否启用
     */
    fun isFeatureEnabled(feature: String): Boolean {
        return when(feature) {
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
}
