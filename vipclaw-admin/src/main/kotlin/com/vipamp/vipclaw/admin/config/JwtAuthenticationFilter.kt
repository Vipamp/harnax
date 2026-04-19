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
            val token = resolveToken(request)
            if (token != null) {
                if (tokenBlacklistService.isBlacklisted(token)) {
                    log.debug("Token 已在黑名单中，拒绝访问")
                    SecurityContextHolder.clearContext()
                    filterChain.doFilter(request, response)
                    return
                }
                if (jwtUtil.validateToken(token)) {
                    val userId = jwtUtil.getUserIdFromToken(token)
                    val username = jwtUtil.getUsernameFromToken(token)
                    val authentication = UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        ArrayList()
                    )
                    authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authentication
                    log.debug("JWT 认证成功，userId: {}, username: {}", userId, username)
                } else {
                    log.debug("Token 无效或已过期")
                }
            }
        } catch (e: Exception) {
            log.error("JWT 认证失败：{}", e.message)
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
