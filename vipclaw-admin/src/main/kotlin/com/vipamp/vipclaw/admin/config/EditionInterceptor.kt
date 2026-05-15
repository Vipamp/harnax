package com.vipamp.vipclaw.admin.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Edition control interceptor
 * Checks @RequiresEdition annotation and validates current edition before request processing
 *
 * If the API is annotated with @RequiresEdition and current edition is not in the supported list,
 * returns 404 Not Found to avoid exposing system capabilities
 */
@Component
class EditionInterceptor(
    private val editionUtil: EditionUtil,
) : HandlerInterceptor {

    private val log = LoggerFactory.getLogger(EditionInterceptor::class.java)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        // Only process method-level requests
        if (handler !is HandlerMethod) {
            return true
        }

        // Get @RequiresEdition annotation at method or class level
        val methodAnnotation = handler.getMethodAnnotation(RequiresEdition::class.java)
        val classAnnotation = handler.beanType.getAnnotation(RequiresEdition::class.java)
        val annotation = methodAnnotation ?: classAnnotation ?: return true

        // Check if annotation value is empty
        if (annotation.value.isEmpty()) {
            log.error("@RequiresEdition must not be empty on ${handler.method}")
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"code":500,"message":"Server configuration error"}""")
            return false
        }

        // Validate current edition
        val currentEdition = editionUtil.getCurrentEdition()
        if (currentEdition in annotation.value) {
            return true
        }

        // Edition mismatch, return 404
        log.warn("Access denied: edition=$currentEdition, required=${annotation.value.contentToString()}, path=${request.requestURI}")
        response.status = HttpServletResponse.SC_NOT_FOUND
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("""{"code":404,"message":"Feature not supported in current edition"}""")
        return false
    }
}
