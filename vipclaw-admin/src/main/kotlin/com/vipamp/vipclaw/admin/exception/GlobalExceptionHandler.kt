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
 * 全局异常处理器
 * 统一处理所有Controller层抛出的异常，避免敏感信息泄露
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * 处理业务异常
     * 业务异常是预期的异常，只返回业务错误码和消息
     */
    @ExceptionHandler(BizException::class)
    fun handleBizException(ex: BizException): ResultVo<Void> {
        log.warn("业务异常: code={}, message={}", ex.code, ex.message)
        return ResultVo.error(ex.code, ex.message ?: "业务处理失败")
    }

    /**
     * 处理参数校验异常
     * 提取第一个校验错误返回给用户
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidationException(ex: MethodArgumentNotValidException): ResultVo<Void> {
        val message = ex.bindingResult.fieldErrors.stream()
            .map { error -> "${error.field}: ${error.defaultMessage}" }
            .findFirst()
            .orElse("参数校验失败")
        log.warn("参数校验失败: {}", message)
        return ResultVo.error(400, message)
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResultVo<Void> {
        log.warn("非法参数: {}", ex.message)
        return ResultVo.error(400, ex.message ?: "参数错误")
    }

    /**
     * 处理数据库访问异常
     * 避免将数据库错误细节暴露给前端
     */
    @ExceptionHandler(DataAccessException::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleDataAccessException(ex: DataAccessException): ResultVo<Void> {
        log.error("数据库访问异常", ex)
        return ResultVo.error(500, "数据库操作失败，请稍后重试")
    }

    /**
     * 处理SQL异常
     */
    @ExceptionHandler(SQLException::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleSQLException(ex: SQLException): ResultVo<Void> {
        log.error("SQL异常", ex)
        return ResultVo.error(500, "数据库操作失败，请稍后重试")
    }

    /**
     * 处理404异常
     */
    @ExceptionHandler(NoHandlerFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNoHandlerFoundException(ex: NoHandlerFoundException): ResultVo<Void> {
        log.warn("请求的资源不存在: {}", ex.requestURL)
        return ResultVo.error(404, "请求的资源不存在")
    }

    /**
     * 处理请求方法不支持异常
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    fun handleMethodNotSupportedException(ex: HttpRequestMethodNotSupportedException): ResultVo<Void> {
        log.warn("不支持的请求方法: {}", ex.method)
        return ResultVo.error(405, "不支持的请求方法")
    }

    /**
     * 处理消息不可读异常（JSON解析失败等）
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleHttpMessageNotReadableException(ex: HttpMessageNotReadableException): ResultVo<Void> {
        log.warn("请求消息解析失败: {}", ex.message)
        return ResultVo.error(400, "请求参数格式错误")
    }

    /**
     * 处理缺少请求参数异常
     */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMissingServletRequestParameterException(ex: MissingServletRequestParameterException): ResultVo<Void> {
        log.warn("缺少请求参数: {}", ex.parameterName)
        return ResultVo.error(400, "缺少必需参数: ${ex.parameterName}")
    }

    /**
     * 处理文件上传大小超限异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMaxUploadSizeExceededException(ex: MaxUploadSizeExceededException): ResultVo<Void> {
        log.warn("文件上传大小超限")
        return ResultVo.error(400, "上传文件大小超过限制")
    }

    /**
     * 处理所有未捕获的运行时异常
     * 作为兜底处理，避免将敏感信息和技术细节暴露给前端
     */
    @ExceptionHandler(RuntimeException::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleRuntimeException(ex: RuntimeException): ResultVo<Void> {
        log.error("系统运行时异常", ex)
        return ResultVo.error(500, "系统内部错误，请稍后重试")
    }

    /**
     * 处理所有未捕获的异常
     * 最终的兜底处理
     */
    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleException(ex: Exception): ResultVo<Void> {
        log.error("系统异常", ex)
        return ResultVo.error(500, "系统错误，请稍后重试")
    }
}
