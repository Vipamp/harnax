package com.agnetix.harnax.scheduler.config

import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.scheduler.support.InternalCallerInterceptor
import com.agnetix.harnax.scheduler.support.SchedulerBizException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper

/**
 * The web-layer shape of this service: what an inbound request has to present to be served at all, and what
 * an error answer looks like once it is served. The error half is the same form admin's clients already parse:
 * a `ResultVo` body with a business code, never a stack trace.
 *
 * Only the two handlers the task domain needs are here. [SchedulerBizException] is the ported form of the
 * refusals admin's task services threw (`40901`/`40902`/`40903` and the validation texts), and the
 * validation handler answers a rejected request body the way `GlobalExceptionHandler` in admin does — the
 * message goes through `field: <first failure>`, with "Validation failed" as the fallback, because the
 * webui shows `.message` verbatim and any rewording here is a visible contract change. The catch-all
 * handlers admin carries (database, 404/405, the runtime fallback) were deliberately not copied: this
 * module's own controllers still catch their exceptions themselves, and pulling a blanket handler in here
 * would change what those endpoints answer today.
 *
 * Release 2 task 5 added the internal-caller interceptor registration to this class — the advice and the
 * `WebMvcConfigurer` belong to the same "what does a request to this service look like" question, and keeping
 * them together means one file answers it. The provider it authenticates with is declared in [SchedulerConfig].
 */
@RestControllerAdvice
class SchedulerWebConfig(
    private val tokenProvider: InternalTokenProvider,
    private val objectMapper: ObjectMapper,
) : WebMvcConfigurer {

    private val log = LoggerFactory.getLogger(SchedulerWebConfig::class.java)

    /**
     * The C4 gate, over the whole of the `/api/scheduler` surface.
     *
     * The pattern list comes from the interceptor so that one constant says what is protected, and the
     * interceptor is built here rather than component-scanned because it is per-request machinery with a
     * dependency on this service's own [InternalTokenProvider] — see [SchedulerConfig] for why that bean is
     * declared by hand.
     */
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(InternalCallerInterceptor(tokenProvider, objectMapper))
            .addPathPatterns(InternalCallerInterceptor.PROTECTED_PATHS)
    }

    /**
     * A business refusal is expected: report its own code and message and nothing else.
     */
    @ExceptionHandler(SchedulerBizException::class)
    fun handleBizException(ex: SchedulerBizException): ResultVo<Void> {
        log.warn("Business exception: code={}, message={}", ex.code, ex.message)
        return ResultVo.error(ex.code, ex.message)
    }

    /**
     * Bean-validation failure on a request body: the first field error, in admin's `field: message` form.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidationException(ex: MethodArgumentNotValidException): ResultVo<Void> {
        val message = ex.bindingResult.fieldErrors.stream()
            .map { error -> "${error.field}: ${error.defaultMessage}" }
            .findFirst()
            .orElse("Validation failed")
        log.warn("Validation failed: {}", message)
        return ResultVo.error(400, message)
    }
}
