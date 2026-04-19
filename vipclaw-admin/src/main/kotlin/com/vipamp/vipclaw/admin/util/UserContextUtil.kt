package com.vipamp.vipclaw.admin.util

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * 用户上下文工具类
 * 用于获取当前登录用户信息
 *
 * @author vipamp
 * @since 2026-03-19
 */
@Component
class UserContextUtil {

    private val log = LoggerFactory.getLogger(UserContextUtil::class.java)

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "

        /**
         * 获取当前请求
         *
         * @return 当前 HttpServletRequest
         */
        fun getCurrentRequest(): HttpServletRequest? {
            return try {
                val attributes = RequestContextHolder.getRequestAttributes() as ServletRequestAttributes?
                attributes?.request
            } catch (e: Exception) {
                LoggerFactory.getLogger(UserContextUtil::class.java).error("获取当前请求失败：{}", e.message)
                null
            }
        }

        /**
         * 从请求头中获取 Token
         *
         * @return JWT Token
         */
        fun getToken(): String? {
            val request = getCurrentRequest()
            if (request != null) {
                val bearerToken = request.getHeader(AUTHORIZATION_HEADER)
                if (bearerToken != null && bearerToken.startsWith(BEARER_PREFIX)) {
                    return bearerToken.substring(BEARER_PREFIX.length)
                }
            }
            return null
        }

        /**
         * 获取当前登录用户名
         *
         * @param jwtUtil JWT 工具类
         * @return 用户名，未登录返回 null
         */
        fun getCurrentUsername(jwtUtil: JwtUtil): String {
            return try {
                val token = getToken()
                if (token != null && jwtUtil.validateToken(token)) {
                    jwtUtil.getUsernameFromToken(token)
                } else {
                    throw RuntimeException("未登录")
                }
            } catch (e: Exception) {
                throw RuntimeException("未登录")
            }
        }

        /**
         * 获取当前登录用户 ID
         *
         * @param jwtUtil JWT 工具类
         * @return 用户 ID，未登录返回 null
         */
        fun getCurrentUserId(jwtUtil: JwtUtil): Long? {
            return try {
                val token = getToken()
                if (token != null && jwtUtil.validateToken(token)) {
                    jwtUtil.getUserIdFromToken(token)
                } else {
                    null
                }
            } catch (e: Exception) {
                LoggerFactory.getLogger(UserContextUtil::class.java).error("获取当前用户ID失败：{}", e.message)
                null
            }
        }
    }
}
