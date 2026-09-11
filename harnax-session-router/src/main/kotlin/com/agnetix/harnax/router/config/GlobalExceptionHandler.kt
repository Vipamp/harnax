package com.agnetix.harnax.router.config

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.error.HarnaxException
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Global fallback for uncaught exceptions in non-streaming endpoints.
 * SSE proxy endpoints handle errors themselves by emitting ErrorChatEvent.
 *
 * The HTTP status stays 200 and the failure travels in the body's `code`: the webui's request layer
 * decides "this failed" from `code != 200` and only reads a body from a 2xx response, so answering
 * 502 here would replace the real reason with a generic transport error on the user's screen.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(HarnaxException::class)
    fun handleHarnaxException(e: HarnaxException): ResultVo<Any> {
        log.error("Unhandled HarnaxException: code={}, message={}", e.code, e.message, e)
        return ResultVo.error(code = e.code.toIntOrNull() ?: 500, message = e.message)
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResultVo<Any> {
        log.error("Unhandled exception: {}", e.message, e)
        return ResultVo.error(e.message ?: "Internal server error")
    }
}
