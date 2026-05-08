package com.vipamp.vipclaw.admin.interceptor

import com.vipamp.vipclaw.admin.context.TenantContext
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.i18n.MessageUtil
import com.vipamp.vipclaw.admin.mapper.UserTenantMapper
import com.vipamp.vipclaw.admin.security.SecurityUtils
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 租户上下文拦截器
 * 解析请求头中的 X-Tenant-ID，验证用户权限，并注入 TenantContext
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

        // 如果没有租户头，跳过（某些公开接口不需要租户上下文）
        if (tenantIdHeader.isNullOrBlank()) {
            return true
        }

        val tenantId = tenantIdHeader.toLongOrNull()
            ?: throw BizException(messageUtil.getMessage("error.tenant.invalid_id"))

        val currentUser = SecurityUtils.getCurrentUser()
            ?: throw BizException(messageUtil.getMessage("error.auth.not_logged_in"))

        // 全局管理员跳过验证
        if (currentUser.isAdmin == 1) {
            TenantContext.setTenantId(tenantId)
            return true
        }

        // 验证用户是否属于该租户
        val userTenant = userTenantMapper.selectByUserIdAndTenantId(currentUser.id, tenantId)

        if (userTenant == null) {
            throw BizException(messageUtil.getMessage("error.tenant.access_denied"))
        }

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
        // 清理 ThreadLocal 防止内存泄漏
        TenantContext.clear()
    }
}
