package com.agnetix.harnax.admin.util

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * User context utility
 * Used to get current logged-in user information
 */
@Component
class UserContextUtil {

    private val log = LoggerFactory.getLogger(UserContextUtil::class.java)

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "

        /**
         * Get current request
         *
         * @return Current HttpServletRequest
         */
        fun getCurrentRequest(): HttpServletRequest? = try {
            val attributes = RequestContextHolder.getRequestAttributes() as ServletRequestAttributes?
            attributes?.request
        } catch (e: Exception) {
            LoggerFactory.getLogger(UserContextUtil::class.java).error("Failed to get current request: {}", e.message)
            null
        }

        /**
         * Get token from request header
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
         * Get current logged-in username
         *
         * @param jwtUtil JWT utility
         * @return Username, null if not logged in
         */
        fun getCurrentUsername(jwtUtil: JwtUtil): String = try {
            val token = getToken()
            if (token != null && jwtUtil.validateToken(token)) {
                jwtUtil.getUsernameFromToken(token)
            } else {
                throw RuntimeException("Not logged in")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("Not logged in")
        }

        /**
         * Get current logged-in user ID
         *
         * @param jwtUtil JWT utility
         * @return User ID, null if not logged in
         */
        fun getCurrentUserId(jwtUtil: JwtUtil): Long? = try {
            val token = getToken()
            val valid = jwtUtil.validateToken(token!!)
            if (token != null && valid) {
                jwtUtil.getUserIdFromToken(token)
            } else {
                null
            }
        } catch (e: Exception) {
            LoggerFactory.getLogger(UserContextUtil::class.java).error("Failed to get current user ID: {}", e.message)
            null
        }
    }
}
