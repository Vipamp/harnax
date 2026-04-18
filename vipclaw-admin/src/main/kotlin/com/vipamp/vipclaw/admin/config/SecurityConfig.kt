package com.vipamp.vipclaw.admin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Spring Security 配置
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter
) {

    @Bean
    @Throws(Exception::class)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // 禁用 CSRF
            .csrf { it.disable() }
            // 禁用 Form Login (防止 302 重定向)
            .formLogin { it.disable() }
            // 禁用 HTTP Basic
            .httpBasic { it.disable() }
            // 配置 CORS（必须在 JWT 过滤器之前）
            .cors { it.configurationSource(corsConfigurationSource()) }
            // 配置授权规则
            .authorizeHttpRequests { auth ->
                auth
                    // 放行登录、登出和验证码接口
                    .requestMatchers("/auth/login", "/auth/logout", "/auth/captcha").permitAll()
                    // Swagger 文档
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    // 其他请求需要认证
                    .anyRequest().authenticated()
            }
            // 禁用 Session
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // 添加 JWT 过滤器
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            // 配置异常处理
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { request, response, authException ->
                    // Token 无效或未提供时的处理
                    response.status = 401
                    response.contentType = "application/json;charset=UTF-8"
                    response.writer.write("""{"success":false,"errorCode":401,"errorMessage":"未授权或 Token 无效"}""")
                }
            }

        return http.build()
    }

    /**
     * CORS 配置源
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()

        // 允许的源
        configuration.allowedOriginPatterns = listOf("*")
        // 允许的方法
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
        // 允许的请求头
        configuration.allowedHeaders = listOf(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Origin",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers"
        )
        // 暴露的响应头
        configuration.exposedHeaders = listOf(
            "Access-Control-Allow-Origin",
            "Access-Control-Allow-Credentials",
            "Authorization"
        )
        // 允许凭证
        configuration.allowCredentials = true
        // 预检请求缓存时间
        configuration.maxAge = 3600L

        // 注册到所有路径
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)

        return source
    }
}
