package com.agnetix.harnax.auth

import com.agnetix.harnax.common.dto.ResultVo
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import tools.jackson.databind.ObjectMapper

class RateLimitInterceptor(
    private val rateLimitChecker: RateLimitChecker,
    private val objectMapper: ObjectMapper,
) : HandlerInterceptor {

    private val log = LoggerFactory.getLogger(RateLimitInterceptor::class.java)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod) return true

        val context = AuthContextHolder.get() ?: return true

        if (context.callerType != CallerType.EXTERNAL_API) return true

        val limit = context.rateLimitPerMinute ?: return true

        if (!rateLimitChecker.tryAcquire(context.callerId, limit)) {
            log.warn("Rate limit exceeded for caller '${context.callerId}' (limit: $limit/min) on ${request.requestURI}")
            response.status = 429 // Too Many Requests
            response.contentType = "application/json"
            response.setHeader("Retry-After", "60")
            response.writer.write(
                objectMapper.writeValueAsString(
                    ResultVo.error<String>(429, "Rate limit exceeded. Limit: $limit requests per minute"),
                ),
            )
            return false
        }

        return true
    }
}
