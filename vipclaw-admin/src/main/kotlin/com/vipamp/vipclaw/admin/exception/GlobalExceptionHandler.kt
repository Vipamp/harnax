package com.vipamp.vipclaw.admin.exception

import com.vipamp.vipclaw.admin.dto.ResultVo
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.LocalDateTime

/**
 * 全局异常处理器
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BizException::class)
    fun handleBizException(ex: BizException): ResultVo<Void> {
        return ResultVo.error(ex.code, ex.message ?: "Unknown error")
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(ex: MethodArgumentNotValidException): ResultVo<Void> {
        val message = ex.bindingResult.fieldErrors.stream()
            .map { error -> "${error.field}: ${error.defaultMessage}" }
            .findFirst()
            .orElse("参数校验失败")
        return ResultVo.error(400, message)
    }

    /**
     * 处理运行时异常
     */
    @ExceptionHandler(RuntimeException::class)
    fun handleRuntimeException(ex: RuntimeException): ResponseEntity<Map<String, Any>> {
        val error = mapOf(
            "timestamp" to LocalDateTime.now().toString(),
            "status" to 500,
            "error" to "Internal Server Error",
            "message" to (ex.message ?: "Unknown error")
        )

        return ResponseEntity.status(500).body(error)
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<Map<String, Any>> {
        val error = mapOf(
            "timestamp" to LocalDateTime.now().toString(),
            "status" to 400,
            "error" to "Bad Request",
            "message" to (ex.message ?: "Invalid argument")
        )

        return ResponseEntity.status(400).body(error)
    }
}
