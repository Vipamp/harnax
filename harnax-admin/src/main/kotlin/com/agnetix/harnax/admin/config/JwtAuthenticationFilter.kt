package com.agnetix.harnax.admin.config

import com.agnetix.harnax.admin.service.SysTokenBlacklistService
import com.agnetix.harnax.admin.util.JwtUtil
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
 * JWT authentication filter
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
                log.info("[JWT Filter] Request without token: {}", requestURI)
            } else {
                log.info("[JWT Filter] Start validating token, URI: {}, token prefix: {}", requestURI, token.take(20))
                
                // Check if token is blacklisted
                val res = tokenBlacklistService.isBlacklisted(token)
                if (res) {
                    log.warn("[JWT Filter] Token is in blacklist, access denied: {}", requestURI)
                    // Throw exception, handled uniformly by authenticationEntryPoint in SecurityConfig
                    throw org.springframework.security.authentication.AuthenticationServiceException("Token has expired, please login again")
                }

                // Validate token
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
                    log.info("[JWT Filter] JWT authentication successful, userId: {}, username: {}, URI: {}", userId, username, requestURI)
                } else {
                    log.warn("[JWT Filter] Token is invalid or expired, URI: {}, token prefix: {}", requestURI, token.take(20))
                    // Throw exception for invalid or expired token
                    throw org.springframework.security.authentication.AuthenticationServiceException("Token is invalid or expired, please login again")
                }
            }
        } catch (e: org.springframework.security.authentication.AuthenticationServiceException) {
            // Re-throw authentication exceptions to be handled by SecurityConfig
            log.error("[JWT Filter] Authentication exception: {}", e.message)
            throw e
        } catch (e: Exception) {
            log.error("[JWT Filter] JWT authentication failed: {}", e.message, e)
            // For other exceptions, throw as authentication exception
            throw org.springframework.security.authentication.AuthenticationServiceException("Authentication failed: ${e.message}")
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
