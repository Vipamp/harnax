package com.vipamp.vipclaw.admin.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 版本工具类
 * 用于在代码中判断当前打包的版本
 */
@Component
class EditionUtil {
    
    companion object {
        const val EDITION_PERSONAL = "personal"
        const val EDITION_ENTERPRISE = "enterprise"
        const val EDITION_PUBLIC = "public"
    }
    
    @Value("\${edition.current}")
    private lateinit var currentEdition: String
    
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
}
