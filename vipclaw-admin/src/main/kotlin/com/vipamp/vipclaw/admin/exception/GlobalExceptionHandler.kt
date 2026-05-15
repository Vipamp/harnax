package com.vipamp.vipclaw.admin.exception

import com.vipamp.vipclaw.admin.dto.ResultVo
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.NoHandlerFoundException
import java.sql.SQLException

/**
 * Global exception handler
 * Uniformly handle all exceptions thrown at Controller layer to avoid sensitive information leakage
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * Handle business exception
     * Business exception is expected, only return business error code and message
     */
    @ExceptionHandler(BizException::class)
    fun handleBizException(ex: BizException): ResultVo<Void> {
        log.warn("Business exception: code={}, message={}", ex.code, ex.message)
        return ResultVo.error(ex.code, ex.message ?: "Business operation failed")
    }

    /**
     * Handle validation exception
     * Extract first validation error and return to user
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

    /**
     * Handle illegal argument exception
     */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResultVo<Void> {
        log.warn("Illegal argument: {}", ex.message)
        return ResultVo.error(400, ex.message ?: "Parameter error")
    }

    /**
     * Handle database access exception
     * Avoid exposing database error details to frontend
     */
    @ExceptionHandler(DataAccessException::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleDataAccessException(ex: DataAccessException): ResultVo<Void> {
        log.error("Database access exception", ex)
        return ResultVo.error(500, "Database operation failed, please try again later")
    }

    /**
     * Handle SQL exception
     */
    @ExceptionHandler(SQLException::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleSQLException(ex: SQLException): ResultVo<Void> {
        log.error("SQL exception", ex)
        return ResultVo.error(500, "Database operation failed, please try again later")
    }

    /**
     * Handle 404 exception
     */
    @ExceptionHandler(NoHandlerFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNoHandlerFoundException(ex: NoHandlerFoundException): ResultVo<Void> {
        log.warn("Requested resource not found: {}", ex.requestURL)
        return ResultVo.error(404, "Requested resource not found")
    }

    /**
     * Handle request method not supported exception
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    fun handleMethodNotSupportedException(ex: HttpRequestMethodNotSupportedException): ResultVo<Void> {
        log.warn("Request method not supported: {}", ex.method)
        return ResultVo.error(405, "Request method not supported")
    }

    /**
     * Handle message not readable exception (JSON parsing failure, etc.)
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleHttpMessageNotReadableException(ex: HttpMessageNotReadableException): ResultVo<Void> {
        log.warn("Request message parsing failed: {}", ex.message)
        return ResultVo.error(400, "Request parameter format error")
    }

    /**
     * Handle missing request parameter exception
     */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMissingServletRequestParameterException(ex: MissingServletRequestParameterException): ResultVo<Void> {
        log.warn("Missing request parameter: {}", ex.parameterName)
        return ResultVo.error(400, "Missing required parameter: ${ex.parameterName}")
    }

    /**
     * Handle file upload size exceeded exception
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMaxUploadSizeExceededException(ex: MaxUploadSizeExceededException): ResultVo<Void> {
        log.warn("File upload size exceeded")
        return ResultVo.error(400, "File size exceeds limit")
    }

    /**
     * Handle all uncaught runtime exceptions
     * Fallback handler to avoid exposing sensitive information and technical details to frontend
     */
    @ExceptionHandler(RuntimeException::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleRuntimeException(ex: RuntimeException): ResultVo<Void> {
        log.error("System runtime exception", ex)
        return ResultVo.error(500, "System internal error, please try again later")
    }

    /**
     * Handle all uncaught exceptions
     * Final fallback handler
     */
    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleException(ex: Exception): ResultVo<Void> {
        log.error("System exception", ex)
        return ResultVo.error(500, "System error, please try again later")
    }
}
