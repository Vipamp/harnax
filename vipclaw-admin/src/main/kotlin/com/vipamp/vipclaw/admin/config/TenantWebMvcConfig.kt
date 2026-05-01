package com.vipamp.vipclaw.admin.config

import com.vipamp.vipclaw.admin.interceptor.TenantInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 配置
 * 注册租户拦截器
 */
@Configuration
class TenantWebMvcConfig(
    private val tenantInterceptor: TenantInterceptor,
    private val editionInterceptor: EditionInterceptor
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        // 注册租户拦截器
        registry.addInterceptor(tenantInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/auth/login",
                "/api/auth/captcha",
                "/api/auth/logout"
            )
        
        // 注册版本控制拦截器
        registry.addInterceptor(editionInterceptor)
            .addPathPatterns("/api/**")
    }
}
