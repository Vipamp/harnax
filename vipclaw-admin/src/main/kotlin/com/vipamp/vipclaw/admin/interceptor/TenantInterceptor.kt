package com.vipamp.vipclaw.admin.interceptor

import com.vipamp.vipclaw.admin.common.BusinessException
import com.vipamp.vipclaw.admin.context.TenantContext
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
    private val userTenantMapper: UserTenantMapper
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val tenantIdHeader = request.getHeader("X-Tenant-ID")

        // 如果没有租户头，跳过（某些公开接口不需要租户上下文）
        if (tenantIdHeader.isNullOrBlank()) {
            return true
        }

        val tenantId = tenantIdHeader.toLongOrNull()
            ?: throw BusinessException("无效的租户ID")

        val currentUser = SecurityUtils.getCurrentUser()
            ?: throw BusinessException("用户未登录")

        // 全局管理员跳过验证
        if (currentUser.isAdmin == 1) {
            TenantContext.setTenantId(tenantId)
            return true
        }

        // 验证用户是否属于该租户
        val userTenant = userTenantMapper.selectByUserIdAndTenantId(currentUser.id, tenantId)

        if (userTenant == null) {
            throw BusinessException("无权访问该租户")
        }

        if (userTenant.status == 0) {
            throw BusinessException("您在该租户下已被禁用")
        }

        TenantContext.setTenantId(tenantId)
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        // 清理 ThreadLocal 防止内存泄漏
        TenantContext.clear()
    }
}
