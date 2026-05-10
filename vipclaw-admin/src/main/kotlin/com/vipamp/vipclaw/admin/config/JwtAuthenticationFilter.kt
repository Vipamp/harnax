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
    private val tokenBlacklistService: SysTokenBlacklistService,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestURI = request.requestURI
        try {
            val token = resolveToken(request)
            if (token == null) {
                log.info("[JWT Filter] 请求未携带 Token: {}", requestURI)
            } else {
                log.info("[JWT Filter] 开始验证 Token, URI: {}, token 前缀: {}", requestURI, token.take(20))
                val res = tokenBlacklistService.isBlacklisted(token)
                if (res) {
                    log.warn("[JWT Filter] Token 已在黑名单中，拒绝访问: {}", requestURI)
                    // 抛出异常，由 SecurityConfig 的 authenticationEntryPoint 统一处理
                    throw org.springframework.security.authentication.AuthenticationServiceException("Token 已失效，请重新登录")
                }

                if (jwtUtil.validateToken(token)) {
                    val userId = jwtUtil.getUserIdFromToken(token)
                    val username = jwtUtil.getUsernameFromToken(token)
                    val authentication = UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        ArrayList(),
                    )
                    authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authentication
                    log.info("[JWT Filter] JWT 认证成功，userId: {}, username: {}, URI: {}", userId, username, requestURI)
                } else {
                    log.warn("[JWT Filter] Token 无效或已过期，URI: {}, token 前缀: {}", requestURI, token.take(20))
                }
            }
        } catch (e: Exception) {
            log.error("[JWT Filter] JWT 认证失败：{}", e.message, e)
        }

        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader("Authorization")
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7)
        }
        return null
    }
}
