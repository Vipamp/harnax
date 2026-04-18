package com.vipamp.vipclaw.admin.config

import com.vipamp.vipclaw.admin.service.SysTokenBlacklistService
import com.vipamp.vipclaw.admin.util.JwtUtil
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

/**
 * JWT 认证过滤器
 */
@Component
class JwtAuthenticationFilter(
    private val jwtUtil: JwtUtil,
    private val tokenBlacklistService: SysTokenBlacklistService
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            // 从请求头中获取 Token
            val token = resolveToken(request)

            if (token != null) {
                // 检查 Token 是否在黑名单中（已退出）
                if (tokenBlacklistService.isBlacklisted(token)) {
                    log.debug("Token 已在黑名单中，拒绝访问")
                    SecurityContextHolder.clearContext()
                    filterChain.doFilter(request, response)
                    return
                }

                if (jwtUtil.validateToken(token)) {
                    // 解析 Token 获取用户信息
                    val userId = jwtUtil.getUserIdFromToken(token)
                    val username = jwtUtil.getUsernameFromToken(token)

                    // 设置 Spring Security 上下文
                    val authentication = UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        ArrayList()
                    )
                    authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authentication

                    log.debug("JWT 认证成功，userId: {}, username: {}", userId, username)
                } else {
                    // Token 无效或已过期，不清除上下文，让后续处理
                    log.debug("Token 无效或已过期")
                }
            }
        } catch (e: Exception) {
            log.error("JWT 认证失败：{}", e.message)
            // 不清除 SecurityContextHolder，让 Spring Security 的 exceptionHandler 处理
        }

        filterChain.doFilter(request, response)
    }

    /**
     * 从请求中解析 Token
     */
    private fun resolveToken(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader("Authorization")

        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7)
        }

        return null
    }
}
