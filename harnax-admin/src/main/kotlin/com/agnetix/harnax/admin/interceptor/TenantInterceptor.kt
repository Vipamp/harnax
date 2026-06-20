package com.agnetix.harnax.admin.interceptor

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.mapper.UserTenantMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Tenant context interceptor
 * Parse X-Tenant-ID from request header, verify user permissions, and inject TenantContext
 */
@Component
class TenantInterceptor(
    private val userTenantMapper: UserTenantMapper,
    private val messageUtil: MessageUtil,
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val tenantIdHeader = request.getHeader("X-Tenant-ID")

        // If no tenant header, skip (some public APIs don't need tenant context)
        if (tenantIdHeader.isNullOrBlank()) {
            return true
        }

        val tenantId = tenantIdHeader.toLongOrNull()
            ?: throw BizException(messageUtil.getMessage("error.tenant.invalid_id"))

        val currentUser = SecurityUtils.getCurrentUser()
            ?: throw BizException(messageUtil.getMessage("error.auth.not_logged_in"))

        // Global admin skip verification
        if (currentUser.isAdmin == 1) {
            TenantContext.setTenantId(tenantId)
            return true
        }

        // Verify if user belongs to this tenant
        val userTenant = userTenantMapper.selectByUserIdAndTenantId(currentUser.id, tenantId) ?: throw BizException(
            messageUtil.getMessage("error.tenant.access_denied"),
        )

        if (userTenant.status == 0) {
            throw BizException(messageUtil.getMessage("error.tenant.user_disabled"))
        }

        TenantContext.setTenantId(tenantId)
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        // Clean up ThreadLocal to prevent memory leak
        TenantContext.clear()
    }
}
