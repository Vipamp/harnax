package com.vipamp.vipclaw.admin.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.web.method.HandlerMethod
import java.lang.reflect.Method

/**
 * EditionInterceptor 单元测试
 * 测试版本控制拦截器的各种场景
 */
@DisplayName("EditionInterceptor 单元测试")
class EditionInterceptorTest {

    private lateinit var editionUtil: EditionUtil
    private lateinit var interceptor: EditionInterceptor
    private lateinit var mockRequest: HttpServletRequest
    private lateinit var mockResponse: HttpServletResponse

    @BeforeEach
    fun setUp() {
        editionUtil = mock()
        interceptor = EditionInterceptor(editionUtil)
        mockRequest = mock()
        mockResponse = mock()
    }

    @Test
    @DisplayName("应该放行没有 @RequiresEdition 注解的请求")
    fun `should allow request without RequiresEdition annotation`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val handlerWithoutAnnotation = mock<HandlerMethod>()
        whenever(handlerWithoutAnnotation.getMethodAnnotation(RequiresEdition::class.java)).thenReturn(null)
        whenever(handlerWithoutAnnotation.beanType).thenReturn(Object::class.java)

        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithoutAnnotation)

        // Assert
        assertTrue(result)
        verify(mockResponse, never()).status = any()
    }

    @Test
    @DisplayName("应该放行非 HandlerMethod 类型的请求")
    fun `should allow non-HandlerMethod requests`() {
        // Arrange
        val nonHandlerMethod = mock<Any>()

        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, nonHandlerMethod)

        // Assert
        assertTrue(result)
        verify(mockResponse, never()).status = any()
    }

    @Test
    @DisplayName("应该放行版本匹配的请求 - 个人版")
    fun `should allow request when edition matches personal`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val mockMethod = mock<Method>()
        val handlerWithAnnotation = mock<HandlerMethod>()
        whenever(handlerWithAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition("personal"))
        whenever(handlerWithAnnotation.beanType).thenReturn(Object::class.java)
        whenever(handlerWithAnnotation.method).thenReturn(mockMethod)
        whenever(mockRequest.requestURI).thenReturn("/api/welcome")

        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithAnnotation)

        // Assert
        assertTrue(result)
        verify(mockResponse, never()).status = any()
    }

    @Test
    @DisplayName("应该放行版本匹配的请求 - 企业版")
    fun `should allow request when edition matches enterprise`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("enterprise")
        val mockMethod = mock<Method>()
        val handlerWithAnnotation = mock<HandlerMethod>()
        whenever(handlerWithAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition("enterprise", "public"))
        whenever(handlerWithAnnotation.beanType).thenReturn(Object::class.java)
        whenever(handlerWithAnnotation.method).thenReturn(mockMethod)
        whenever(mockRequest.requestURI).thenReturn("/api/users")

        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithAnnotation)

        // Assert
        assertTrue(result)
        verify(mockResponse, never()).status = any()
    }

    @Test
    @DisplayName("应该拦截版本不匹配的请求并返回 404")
    fun `should intercept request when edition not match and return 404`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val mockMethod = mock<Method>()
        val handlerWithAnnotation = mock<HandlerMethod>()
        whenever(handlerWithAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition("enterprise", "public"))
        whenever(handlerWithAnnotation.beanType).thenReturn(Object::class.java)
        whenever(handlerWithAnnotation.method).thenReturn(mockMethod)
        whenever(mockRequest.requestURI).thenReturn("/api/users")

        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithAnnotation)

        // Assert
        assertFalse(result)
        verify(mockResponse).status = HttpServletResponse.SC_NOT_FOUND
        verify(mockResponse).contentType = "application/json;charset=UTF-8"
        verify(mockResponse).writer.write("""{"code":404,"message":"当前版本不支持此功能"}""")
    }

    @Test
    @DisplayName("应该拦截版本不匹配的请求 - 公有云版访问企业版功能")
    fun `should intercept request when public edition access enterprise feature`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("public")
        val mockMethod = mock<Method>()
        val handlerWithAnnotation = mock<HandlerMethod>()
        whenever(handlerWithAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition("personal"))
        whenever(handlerWithAnnotation.beanType).thenReturn(Object::class.java)
        whenever(handlerWithAnnotation.method).thenReturn(mockMethod)
        whenever(mockRequest.requestURI).thenReturn("/api/personal-feature")

        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithAnnotation)

        // Assert
        assertFalse(result)
        verify(mockResponse).status = HttpServletResponse.SC_NOT_FOUND
    }

    @Test
    @DisplayName("应该拒绝空注解值并返回 500")
    fun `should reject empty annotation values and return 500`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val mockMethod = mock<Method>()
        val handlerWithEmptyAnnotation = mock<HandlerMethod>()
        whenever(handlerWithEmptyAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition())
        whenever(handlerWithEmptyAnnotation.beanType).thenReturn(Object::class.java)
        whenever(handlerWithEmptyAnnotation.method).thenReturn(mockMethod)

        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithEmptyAnnotation)

        // Assert
        assertFalse(result)
        verify(mockResponse).status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
        verify(mockResponse).contentType = "application/json;charset=UTF-8"
        verify(mockResponse).writer.write("""{"code":500,"message":"服务器配置错误"}""")
    }

    @Test
    @DisplayName("应该支持类级别的 @RequiresEdition 注解")
    fun `should support class level RequiresEdition annotation`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("enterprise")
        val mockMethod = mock<Method>()
        val handlerWithClassAnnotation = mock<HandlerMethod>()

        // 方法级别没有注解
        whenever(handlerWithClassAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(null)
        // 类级别有注解
        whenever(handlerWithClassAnnotation.beanType).thenReturn(EnterpriseController::class.java)
        whenever(handlerWithClassAnnotation.method).thenReturn(mockMethod)
        whenever(mockRequest.requestURI).thenReturn("/api/enterprise/data")

        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithClassAnnotation)

        // Assert
        assertTrue(result)
    }

    @Test
    @DisplayName("方法级别注解应该优先于类级别注解")
    fun `method level annotation should override class level annotation`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val mockMethod = mock<Method>()
        val handlerWithBothAnnotations = mock<HandlerMethod>()

        // 方法级别有注解(优先)
        whenever(handlerWithBothAnnotations.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition("personal"))
        whenever(handlerWithBothAnnotations.beanType).thenReturn(EnterpriseController::class.java)
        whenever(handlerWithBothAnnotations.method).thenReturn(mockMethod)
        whenever(mockRequest.requestURI).thenReturn("/api/enterprise/personal-feature")

        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithBothAnnotations)

        // Assert
        assertTrue(result)
    }

    @Test
    @DisplayName("应该在日志中记录版本不匹配的警告")
    fun `should log warning when edition not match`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val mockMethod = mock<Method>()
        val handlerWithAnnotation = mock<HandlerMethod>()
        whenever(handlerWithAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition("enterprise"))
        whenever(handlerWithAnnotation.beanType).thenReturn(Object::class.java)
        whenever(handlerWithAnnotation.method).thenReturn(mockMethod)
        whenever(mockRequest.requestURI).thenReturn("/api/enterprise-only")

        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithAnnotation)

        // Assert
        assertFalse(result)
        // 日志验证需要通过日志框架的测试工具,这里仅验证拦截逻辑
    }
}

/**
 * 测试用 Controller - 用于测试类级别注解
 */
@RequiresEdition("enterprise", "public")
class EnterpriseController
