package com.agnetix.harnax.admin.controller

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

/**
 * HealthController 单元测试
 * 直接实例化 Controller，断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HealthControllerTest {

    private lateinit var controller: HealthController

    @BeforeEach
    fun setUp() {
        controller = HealthController()
    }

    @Nested
    @DisplayName("GET /api/admin/health")
    inner class HealthEndpoint {

        @Test
        @DisplayName("health - 返回 OK")
        fun `health should return OK`() {
            val result = controller.health()

            assertTrue(result.isSuccess())
            assertEquals(200, result.code)
            assertEquals("OK", result.data)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/info")
    inner class InfoEndpoint {

        @Test
        @DisplayName("version - 返回系统版本信息")
        fun `version should return system info`() {
            val result = controller.version()

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("1.0.0-SNAPSHOT", result.data?.version)
        }

        @Test
        @DisplayName("version - 默认字段为空字符串")
        fun `version should return default empty build fields`() {
            val result = controller.version()

            assertTrue(result.isSuccess())
            assertEquals("", result.data?.buildTime)
            assertEquals("", result.data?.gitCommit)
            assertEquals("", result.data?.environment)
        }
    }
}
