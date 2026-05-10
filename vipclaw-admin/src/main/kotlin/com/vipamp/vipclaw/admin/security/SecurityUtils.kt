package com.vipamp.vipclaw.admin.security

import com.vipamp.vipclaw.admin.entity.SysUser
import com.vipamp.vipclaw.admin.mapper.SysUserMapper
import jakarta.annotation.PostConstruct
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * 安全工具类
 * 获取当前登录用户信息
 */
@Component
class SecurityUtils(
    private val sysUserMapper: SysUserMapper,
) {
    companion object {
        private var instance: SecurityUtils? = null

        fun getCurrentUser(): SysUser? {
            val authentication = SecurityContextHolder.getContext().authentication
                ?: return null

            val username = authentication.name
                ?: return null

            return instance?.sysUserMapper?.selectByUsername(username)
        }
    }

    @PostConstruct
    fun init() {
        instance = this
    }
}
