package com.agnetix.harnax.channel.service.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ChannelApiKeyInitializer 单元测试
 * 测试 Channel 启动时获取 Router API Key 的逻辑
 *
 * @author agnetix
 * @since 2026-06-28
 */
@DisplayName("ChannelApiKeyInitializer 测试")
class ChannelApiKeyInitializerTest {

    @Nested
    @DisplayName("静态配置模式")
    inner class StaticConfigTests {

        @Test
        @DisplayName("使用环境变量配置的 API Key")
        fun `should use configured API key from environment`() {
            // Given
            val initializer = ChannelApiKeyInitializer(
                configuredApiKey = "hnx_sk_live_configured_key_12345",
                autoFetch = false,
                adminUrl = "http://localhost:8080",
                adminSecret = "secret",
            )

            // When
            val result = initializer.channelRouterApiKey()

            // Then
            assertEquals("hnx_sk_live_configured_key_12345", result.rawKey)
        }

        @Test
        @DisplayName("配置的 Key 优先于 auto-fetch")
        fun `configured key takes priority over auto-fetch`() {
            // Given
            val initializer = ChannelApiKeyInitializer(
                configuredApiKey = "hnx_sk_live_static_key",
                autoFetch = true,
                adminUrl = "http://localhost:8080",
                adminSecret = "secret",
            )

            // When
            val result = initializer.channelRouterApiKey()

            // Then - should use static key, not auto-fetch
            assertEquals("hnx_sk_live_static_key", result.rawKey)
        }
    }

    @Nested
    @DisplayName("未配置Key异常测试")
    inner class NoKeyConfiguredTests {

        @Test
        @DisplayName("未配置Key且autoFetch关闭时抛出异常")
        fun `should throw when no key configured and autoFetch disabled`() {
            // Given
            val initializer = ChannelApiKeyInitializer(
                configuredApiKey = "",
                autoFetch = false,
                adminUrl = "http://localhost:8080",
                adminSecret = "secret",
            )

            // When & Then
            assertThrows<IllegalStateException> {
                initializer.channelRouterApiKey()
            }
        }

        @Test
        @DisplayName("未配置Key且autoFetch开启但Admin不可达时抛出异常")
        fun `should throw when autoFetch enabled but admin unreachable`() {
            // Given
            val initializer = ChannelApiKeyInitializer(
                configuredApiKey = "",
                autoFetch = true,
                adminUrl = "http://localhost:19999", // non-existent port
                adminSecret = "secret",
            )

            // When & Then - should throw because admin is unreachable
            assertThrows<IllegalStateException> {
                initializer.channelRouterApiKey()
            }
        }
    }

    @Nested
    @DisplayName("ChannelRouterApiKey 值对象测试")
    inner class ChannelRouterApiKeyTests {

        @Test
        @DisplayName("ChannelRouterApiKey 持有 rawKey")
        fun `ChannelRouterApiKey should hold rawKey`() {
            val apiKey = ChannelRouterApiKey("hnx_sk_live_test_key")
            assertEquals("hnx_sk_live_test_key", apiKey.rawKey)
        }
    }
}
