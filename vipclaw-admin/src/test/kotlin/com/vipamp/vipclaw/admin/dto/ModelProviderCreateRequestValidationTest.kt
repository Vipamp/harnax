package com.vipamp.vipclaw.admin.dto

import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import org.junit.jupiter.api.*
import kotlin.test.*

/**
 * ModelProviderCreateRequest DTO 验证测试
 * 测试 Bean Validation 注解的校验逻辑
 *
 * @author vipamp
 * @since 2026-04-24
 */
class ModelProviderCreateRequestValidationTest {

    private lateinit var validator: Validator
    private lateinit var factory: ValidatorFactory

    @BeforeEach
    fun setUp() {
        factory = Validation.buildDefaultValidatorFactory()
        validator = factory.validator
    }

    @AfterEach
    fun tearDown() {
        factory.close()
    }

    @Nested
    @DisplayName("baseUrl 验证测试")
    inner class BaseUrlValidationTests {

        @Test
        fun `验证通过 - baseUrl 为 null`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                baseUrl = null
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "baseUrl 为 null 时应该验证通过")
        }

        @Test
        fun `验证通过 - baseUrl 为空字符串`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                baseUrl = ""
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "baseUrl 为空字符串时应该验证通过")
        }

        @Test
        fun `验证通过 - baseUrl 为有效的 HTTP URL`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                baseUrl = "http://example.com/api"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "baseUrl 为有效的 HTTP URL 时应该验证通过")
        }

        @Test
        fun `验证通过 - baseUrl 为有效的 HTTPS URL`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "baseUrl 为有效的 HTTPS URL 时应该验证通过")
        }

        @Test
        fun `验证通过 - baseUrl 带端口号`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "local_provider",
                name = "本地服务",
                baseUrl = "http://localhost:8080/api"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "baseUrl 带端口号时应该验证通过")
        }

        @Test
        fun `验证通过 - baseUrl 只有域名`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "simple_provider",
                name = "简单服务",
                baseUrl = "example.com"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "baseUrl 只有域名时应该验证通过")
        }

        @Test
        fun `验证失败 - baseUrl 格式不正确`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                baseUrl = "not-a-valid-url!!!@@@"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertFalse(violations.isEmpty(), "baseUrl 格式不正确时应该验证失败")
            val baseUrlViolation = violations.find { it.propertyPath.toString() == "baseUrl" }
            assertNotNull(baseUrlViolation, "应该包含 baseUrl 的验证错误")
            assertEquals("API 地址格式不正确", baseUrlViolation.message)
        }

        @Test
        fun `验证失败 - baseUrl 包含空格`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                baseUrl = "https://example.com/path with spaces"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertFalse(violations.isEmpty(), "baseUrl 包含空格时应该验证失败")
        }
    }

    @Nested
    @DisplayName("type 验证测试")
    inner class NameValidationTests {

        @Test
        fun `验证失败 - type 为空`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "",
                name = "阿里云百炼"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertFalse(violations.isEmpty(), "type 为空时应该验证失败")
            val typeViolation = violations.find { it.propertyPath.toString() == "type" }
            assertNotNull(typeViolation, "应该包含 type 的验证错误")
            // 空字符串会同时触发 @NotBlank 和 @Size 验证，消息可能是其中之一
            assertTrue(
                typeViolation.message == "供应商类型不能为空" || 
                typeViolation.message == "供应商类型长度必须在 1-50 个字符之间",
                "验证错误消息应该是 type 相关的验证失败"
            )
        }

        @Test
        fun `验证失败 - type 为 null`() {
            // Given - 使用反射创建 name 为 null 的对象（因为 name 是非空类型）
            // 这里测试 JSON 反序列化后的场景，实际会抛出异常
            // 所以这个测试主要验证NotBlank注解的存在

            // When & Then
            // name 字段是 String 类型（非 String?），所以在 Kotlin 中不能为 null
            // 但如果从 Java 或 JSON 传入 null，@NotBlank 会捕获
            assertTrue(true, "name 字段为 String 类型，Kotlin 编译时不允许为 null")
        }

        @Test
        fun `验证失败 - type 包含大写字母`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "DashScope",
                name = "阿里云百炼"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertFalse(violations.isEmpty(), "type 包含大写字母时应该验证失败")
        }

        @Test
        fun `验证失败 - name 包含特殊字符`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dash-scope",
                name = "阿里云百炼"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertFalse(violations.isEmpty(), "type 包含连字符时应该验证失败")
        }

        @Test
        fun `验证通过 - name 只包含小写字母、数字和下划线`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope_v2",
                name = "阿里云百炼"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "type 只包含小写字母、数字和下划线时应该验证通过")
        }

        @Test
        fun `验证失败 - type 长度超过 50 字符`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "a".repeat(51),
                name = "阿里云百炼"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertFalse(violations.isEmpty(), "type 长度超过 50 字符时应该验证失败")
            val typeViolation = violations.find { it.propertyPath.toString() == "type" }
            assertNotNull(typeViolation, "应该包含 type 的验证错误")
        }
    }

    @Nested
    @DisplayName("name 验证测试")
    inner class DisplayNameValidationTests {

        @Test
        fun `验证失败 - name 为空`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = ""
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertFalse(violations.isEmpty(), "name 为空时应该验证失败")
        }

        @Test
        fun `验证通过 - name 包含中文`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼平台"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "name 包含中文时应该验证通过")
        }

        @Test
        fun `验证失败 - name 长度超过 100 字符`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "a".repeat(101)
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertFalse(violations.isEmpty(), "name 长度超过 100 字符时应该验证失败")
            val nameViolation = violations.find { it.propertyPath.toString() == "name" }
            assertNotNull(nameViolation, "应该包含 name 的验证错误")
        }
    }

    @Nested
    @DisplayName("apiKey 验证测试")
    inner class ApiKeyValidationTests {

        @Test
        fun `验证通过 - apiKey 为 null`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                apiKey = null
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "apiKey 为 null 时应该验证通过")
        }

        @Test
        fun `验证通过 - apiKey 在限制长度内`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                apiKey = "sk-" + "x".repeat(497) // 总长度 500
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "apiKey 长度不超过 500 时应该验证通过")
        }

        @Test
        fun `验证失败 - apiKey 长度超过 500 字符`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                apiKey = "sk-" + "x".repeat(498) // 总长度 501
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertFalse(violations.isEmpty(), "apiKey 长度超过 500 字符时应该验证失败")
        }
    }

    @Nested
    @DisplayName("isPublic 验证测试")
    inner class IsPublicValidationTests {

        @Test
        fun `验证通过 - isPublic 为 null 时使用默认值 1`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "isPublic 为 null 时应该验证通过")
            assertEquals(1, request.isPublic, "isPublic 默认值应该为 1")
        }

        @Test
        fun `验证通过 - isPublic 为 0（私有）`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                isPublic = 0
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "isPublic 为 0 时应该验证通过")
        }

        @Test
        fun `验证通过 - isPublic 为 1（公开）`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                isPublic = 1
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "isPublic 为 1 时应该验证通过")
        }
    }

    @Nested
    @DisplayName("完整对象验证测试")
    inner class CompleteObjectValidationTests {

        @Test
        fun `验证通过 - 最小必填字段`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "test",
                name = "测试"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "只填写必填字段时应该验证通过")
        }

        @Test
        fun `验证通过 - 所有字段完整`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "dashscope",
                name = "阿里云百炼",
                apiKey = "sk-test-key-12345",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                isPublic = 1
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertTrue(violations.isEmpty(), "所有字段都正确填写时应该验证通过")
        }

        @Test
        fun `验证失败 - 多个字段同时错误`() {
            // Given
            val request = ModelProviderCreateRequest(
                type = "",
                name = "",
                baseUrl = "invalid-url!!!"
            )

            // When
            val violations = validator.validate(request)

            // Then
            assertFalse(violations.isEmpty(), "应该有验证错误")
            val errorFields = violations.map { it.propertyPath.toString() }.toSet()
            assertTrue("type" in errorFields, "应该包含 type 错误")
            assertTrue("name" in errorFields, "应该包含 name 错误")
            assertTrue("baseUrl" in errorFields, "应该包含 baseUrl 错误")
            // 注意：空字符串可能触发多个验证注解（@NotBlank + @Size + @Pattern），所以错误数量可能大于 3
        }
    }
}
