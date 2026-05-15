package com.vipamp.vipclaw.admin.config

import com.vipamp.vipclaw.admin.interceptor.TenantInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC configuration
 * Register tenant interceptor
 */
@Configuration
class TenantWebMvcConfig(
    private val tenantInterceptor: TenantInterceptor,
    private val editionInterceptor: EditionInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        // Register tenant interceptor
        registry.addInterceptor(tenantInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/auth/login",
                "/api/auth/captcha",
                "/api/auth/logout",
            )

        // Register edition control interceptor
        registry.addInterceptor(editionInterceptor)
            .addPathPatterns("/api/**")
    }
}
