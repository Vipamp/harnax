package com.vipamp.vipclaw.admin.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 版本控制拦截器
 * 在请求处理前检查 @RequiresEdition 注解并验证当前版本
 *
 * 如果请求的 API 标注了 @RequiresEdition 且当前版本不在支持列表中,
 * 则返回 404 Not Found,避免暴露系统能力
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
        // 只处理方法级别的请求
        if (handler !is HandlerMethod) {
            return true
        }

        // 获取方法或类级别的 @RequiresEdition 注解
        val methodAnnotation = handler.getMethodAnnotation(RequiresEdition::class.java)
        val classAnnotation = handler.beanType.getAnnotation(RequiresEdition::class.java)
        val annotation = methodAnnotation ?: classAnnotation ?: return true

        // 检查注解值是否为空
        if (annotation.value.isEmpty()) {
            log.error("@RequiresEdition must not be empty on ${handler.method}")
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"code":500,"message":"服务器配置错误"}""")
            return false
        }

        // 验证当前版本
        val currentEdition = editionUtil.getCurrentEdition()
        if (currentEdition in annotation.value) {
            return true
        }

        // 版本不匹配,返回 404
        log.warn("Access denied: edition=$currentEdition, required=${annotation.value.contentToString()}, path=${request.requestURI}")
        response.status = HttpServletResponse.SC_NOT_FOUND
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("""{"code":404,"message":"当前版本不支持此功能"}""")
        return false
    }
}
