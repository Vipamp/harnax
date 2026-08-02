package com.agnetix.harnax.admin.exception

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.NoHandlerFoundException
import java.sql.SQLException

/**
 * GlobalExceptionHandler 单元测试
 * 直接实例化 handler，对每种 @ExceptionHandler 方法逐一断言返回的 ResultVo 状态码和消息
 *
 * @author agnetix
 * @since 2026-06-28
 */
@DisplayName("GlobalExceptionHandler 全局异常处理器测试")
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    /** 用于构造 MethodArgumentNotValidException 的占位方法 */
    @Suppress("UNUSED_PARAMETER", "unused")
    fun dummyMethod(param: String) {
    }

    @Nested
    @DisplayName("业务异常处理测试")
    inner class BizExceptionTests {

        @Test
        @DisplayName("handleBizException - 返回业务异常自定义code和消息")
        fun `handleBizException should return custom code and message`() {
            val ex = BizException(4001, "Agent not found")

            val result = handler.handleBizException(ex)

            assertEquals(4001, result.code)
            assertEquals("Agent not found", result.message)
            assertNull(result.data)
        }

        @Test
        @DisplayName("handleBizException - 默认code为400")
        fun `handleBizException should default code to 400`() {
            val ex = BizException("Operation not allowed")

            val result = handler.handleBizException(ex)

            assertEquals(400, result.code)
            assertEquals("Operation not allowed", result.message)
        }

        @Test
        @DisplayName("handleBizException - 携带cause的业务异常正常处理")
        fun `handleBizException should handle exception with cause`() {
            val ex = BizException(500, "Wrapped failure", IllegalStateException("root cause"))

            val result = handler.handleBizException(ex)

            assertEquals(500, result.code)
            assertEquals("Wrapped failure", result.message)
        }
    }

    @Nested
    @DisplayName("参数校验异常处理测试")
    inner class ValidationExceptionTests {

        private fun buildValidationException(vararg fieldErrors: FieldError): MethodArgumentNotValidException {
            val bindingResult = BeanPropertyBindingResult(Any(), "request")
            fieldErrors.forEach { bindingResult.addError(it) }
            val method = GlobalExceptionHandlerTest::class.java.getDeclaredMethod("dummyMethod", String::class.java)
            return MethodArgumentNotValidException(MethodParameter(method, 0), bindingResult)
        }

        @Test
        @DisplayName("handleValidationException - 提取第一个字段错误")
        fun `handleValidationException should return first field error`() {
            val ex = buildValidationException(
                FieldError("request", "name", "must not be blank"),
                FieldError("request", "age", "must be positive"),
            )

            val result = handler.handleValidationException(ex)

            assertEquals(400, result.code)
            assertEquals("name: must not be blank", result.message)
        }

        @Test
        @DisplayName("handleValidationException - 无字段错误时返回默认消息")
        fun `handleValidationException should return default message when no field errors`() {
            val ex = buildValidationException()

            val result = handler.handleValidationException(ex)

            assertEquals(400, result.code)
            assertEquals("Validation failed", result.message)
        }
    }

    @Nested
    @DisplayName("非法参数异常处理测试")
    inner class IllegalArgumentExceptionTests {

        @Test
        @DisplayName("handleIllegalArgumentException - 返回400和异常消息")
        fun `handleIllegalArgumentException should return 400 with message`() {
            val ex = IllegalArgumentException("id must be positive")

            val result = handler.handleIllegalArgumentException(ex)

            assertEquals(400, result.code)
            assertEquals("id must be positive", result.message)
        }

        @Test
        @DisplayName("handleIllegalArgumentException - 消息为null时返回默认消息")
        fun `handleIllegalArgumentException should return default message when null`() {
            val ex = IllegalArgumentException()

            val result = handler.handleIllegalArgumentException(ex)

            assertEquals(400, result.code)
            assertEquals("Parameter error", result.message)
        }
    }

    @Nested
    @DisplayName("数据库异常处理测试")
    inner class DatabaseExceptionTests {

        @Test
        @DisplayName("handleDataAccessException - 返回500且不暴露数据库细节")
        fun `handleDataAccessException should return 500 without db details`() {
            val ex = DataIntegrityViolationException("duplicate key value violates unique constraint")

            val result = handler.handleDataAccessException(ex)

            assertEquals(500, result.code)
            assertEquals("Database operation failed, please try again later", result.message)
            assertFalse(result.message.contains("duplicate key"), "不应暴露数据库错误细节")
        }

        @Test
        @DisplayName("handleSQLException - 返回500且不暴露SQL细节")
        fun `handleSQLException should return 500 without sql details`() {
            val ex = SQLException("Table 'harnax.agent' doesn't exist")

            val result = handler.handleSQLException(ex)

            assertEquals(500, result.code)
            assertEquals("Database operation failed, please try again later", result.message)
        }
    }

    @Nested
    @DisplayName("404 异常处理测试")
    inner class NotFoundExceptionTests {

        @Test
        @DisplayName("handleNoHandlerFoundException - 返回404")
        fun `handleNoHandlerFoundException should return 404`() {
            val ex = NoHandlerFoundException("GET", "/api/admin/unknown", HttpHeaders())

            val result = handler.handleNoHandlerFoundException(ex)

            assertEquals(404, result.code)
            assertEquals("Requested resource not found", result.message)
        }
    }

    @Nested
    @DisplayName("请求方法不支持异常处理测试")
    inner class MethodNotSupportedTests {

        @Test
        @DisplayName("handleMethodNotSupportedException - 返回405")
        fun `handleMethodNotSupportedException should return 405`() {
            val ex = HttpRequestMethodNotSupportedException("DELETE")

            val result = handler.handleMethodNotSupportedException(ex)

            assertEquals(405, result.code)
            assertEquals("Request method not supported", result.message)
        }
    }

    @Nested
    @DisplayName("消息解析异常处理测试")
    inner class MessageNotReadableTests {

        @Test
        @DisplayName("handleHttpMessageNotReadableException - 返回400")
        fun `handleHttpMessageNotReadableException should return 400`() {
            val ex = HttpMessageNotReadableException(
                "JSON parse error",
                MockHttpInputMessage("{bad json".toByteArray()),
            )

            val result = handler.handleHttpMessageNotReadableException(ex)

            assertEquals(400, result.code)
            assertEquals("Request parameter format error", result.message)
        }
    }

    @Nested
    @DisplayName("缺少请求参数异常处理测试")
    inner class MissingParameterTests {

        @Test
        @DisplayName("handleMissingServletRequestParameterException - 返回400并包含参数名")
        fun `handleMissingServletRequestParameterException should return 400 with parameter name`() {
            val ex = MissingServletRequestParameterException("agentId", "Long")

            val result = handler.handleMissingServletRequestParameterException(ex)

            assertEquals(400, result.code)
            assertEquals("Missing required parameter: agentId", result.message)
        }
    }

    @Nested
    @DisplayName("文件上传超限异常处理测试")
    inner class MaxUploadSizeTests {

        @Test
        @DisplayName("handleMaxUploadSizeExceededException - 返回400")
        fun `handleMaxUploadSizeExceededException should return 400`() {
            val ex = MaxUploadSizeExceededException(10485760L)

            val result = handler.handleMaxUploadSizeExceededException(ex)

            assertEquals(400, result.code)
            assertEquals("File size exceeds limit", result.message)
        }
    }

    @Nested
    @DisplayName("兜底异常处理测试")
    inner class FallbackExceptionTests {

        @Test
        @DisplayName("handleRuntimeException - 返回500且不暴露技术细节")
        fun `handleRuntimeException should return 500 without technical details`() {
            val ex = RuntimeException("NullPointerException at line 42")

            val result = handler.handleRuntimeException(ex)

            assertEquals(500, result.code)
            assertEquals("System internal error, please try again later", result.message)
            assertFalse(result.message.contains("NullPointerException"), "不应暴露技术细节")
        }

        @Test
        @DisplayName("handleException - 返回500兜底消息")
        fun `handleException should return 500 with fallback message`() {
            val ex = Exception("unexpected checked exception")

            val result = handler.handleException(ex)

            assertEquals(500, result.code)
            assertEquals("System error, please try again later", result.message)
        }
    }
}
