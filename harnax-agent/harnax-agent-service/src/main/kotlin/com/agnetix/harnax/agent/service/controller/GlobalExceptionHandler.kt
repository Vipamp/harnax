package com.agnetix.harnax.agent.service.controller

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.error.HarnaxException
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Global fallback for uncaught exceptions in non-streaming endpoints.
 * SSE endpoints handle errors themselves by emitting ErrorChatEvent.
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
