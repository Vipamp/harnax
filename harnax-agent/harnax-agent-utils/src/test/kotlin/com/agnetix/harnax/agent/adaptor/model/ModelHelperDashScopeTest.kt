package com.agnetix.harnax.agent.adaptor.model

import io.agentscope.core.model.ChatModelBase
import io.agentscope.extensions.model.dashscope.DashScopeChatModel
import io.agentscope.extensions.model.dashscope.DashScopeHttpClient
import io.agentscope.extensions.model.dashscope.EndpointType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * DashScope 模型相关单元测试
 * 覆盖 ModelHelper 构建 DashScope ChatModel 的参数传递，
 * 以及 SDK 对多模态模型的端点选择逻辑（回归 url error 问题）
 *
 * @Author: heqingsong
 * @Project: harnax
 */
class ModelHelperDashScopeTest {

    companion object {
        private const val TEST_API_KEY = "sk-b145103b10fa4dccbf84c8f69fdfabf7"
        private const val TEST_MODEL = "qwen3.7-max-2026-06-08"
        private const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com"

        /**
         * 反射读取私有字段
         */
        @Suppress("UNCHECKED_CAST")
        private fun <T> readField(target: Any, fieldName: String): T {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            return field.get(target) as T
        }

        /**
         * 从 DashScopeChatModel 中提取内部 HttpClient
         */
        private fun httpClientOf(model: DashScopeChatModel): DashScopeHttpClient = readField(model, "httpClient")
    }

    private fun dashScopeConfig(
        modelName: String = TEST_MODEL,
        apiKey: String = TEST_API_KEY,
        baseUrl: String? = null,
        stream: Boolean = true,
        enableThinking: Boolean = true,
        enableSearch: Boolean = false,
        forceMultimodalEndpoint: Boolean = false,
    ) = DashScopeChatModelConfig(
        modelName = modelName,
        apiKey = apiKey,
        baseUrl = baseUrl,
        stream = stream,
        enableThinking = enableThinking,
        enableSearch = enableSearch,
        forceMultimodalEndpoint = forceMultimodalEndpoint,
    )

    @Nested
    @DisplayName("createChatModel - DashScope 构建")
    inner class BuildTests {

        @Test
        fun `should return DashScopeChatModel with model name`() {
            val model = ModelHelper.createChatModel(dashScopeConfig())

            assertTrue(model is DashScopeChatModel, "应返回 DashScopeChatModel 实例")
            assertEquals(TEST_MODEL, model.modelName)
        }

        @Test
        fun `should pass apiKey to http client`() {
            val model = ModelHelper.createChatModel(dashScopeConfig()) as DashScopeChatModel

            val apiKey: String = readField(httpClientOf(model), "apiKey")
            assertEquals(TEST_API_KEY, apiKey)
        }

        @Test
        fun `should use SDK default baseUrl when not configured`() {
            val model = ModelHelper.createChatModel(dashScopeConfig(baseUrl = null)) as DashScopeChatModel

            val baseUrl: String = readField(httpClientOf(model), "baseUrl")
            assertEquals(DEFAULT_BASE_URL, baseUrl)
        }

        @Test
        fun `should apply custom baseUrl`() {
            val customUrl = "https://dashscope.custom.example.com"
            val model = ModelHelper.createChatModel(dashScopeConfig(baseUrl = customUrl)) as DashScopeChatModel

            val baseUrl: String = readField(httpClientOf(model), "baseUrl")
            assertEquals(customUrl, baseUrl)
        }

        @Test
        fun `should enable stream and thinking by default`() {
            val model = ModelHelper.createChatModel(dashScopeConfig()) as DashScopeChatModel

            val stream: Boolean = readField(model, "stream")
            val enableThinking: Boolean? = readField(model, "enableThinking")
            val enableSearch: Boolean? = readField(model, "enableSearch")

            assertTrue(stream, "默认应启用流式输出")
            assertEquals(true, enableThinking, "默认应启用思考模式")
            assertEquals(false, enableSearch, "默认不应启用联网搜索")
        }

        @Test
        fun `should honor config stream disabled when thinking is off`() {
            // 思考模式开启时 DashScope 要求必须流式，SDK 会强制 stream=true，
            // 因此关闭流式的断言需在关闭思考模式的前提下验证
            val model = ModelHelper.createChatModel(
                dashScopeConfig(stream = false, enableThinking = false),
                enableThinking = false,
            ) as DashScopeChatModel

            val stream: Boolean = readField(model, "stream")
            assertFalse(stream, "配置关闭流式时应生效")
        }
    }

    @Nested
    @DisplayName("createChatModel - 运行时参数覆盖")
    inner class OverrideTests {

        @Test
        fun `enableThinking parameter should override config`() {
            val model = ModelHelper.createChatModel(dashScopeConfig(enableThinking = true), enableThinking = false)
                as DashScopeChatModel

            val enableThinking: Boolean? = readField(model, "enableThinking")
            assertEquals(false, enableThinking, "运行时参数应覆盖配置值")
        }

        @Test
        fun `enableSearch parameter should override config`() {
            val model = ModelHelper.createChatModel(dashScopeConfig(enableSearch = false), enableSearch = true)
                as DashScopeChatModel

            val enableSearch: Boolean? = readField(model, "enableSearch")
            assertEquals(true, enableSearch, "运行时参数应覆盖配置值")
        }

        @Test
        fun `should fall back to config when parameters are null`() {
            val model = ModelHelper.createChatModel(
                dashScopeConfig(enableThinking = false, enableSearch = true),
                enableThinking = null,
                enableSearch = null,
            ) as DashScopeChatModel

            val enableThinking: Boolean? = readField(model, "enableThinking")
            val enableSearch: Boolean? = readField(model, "enableSearch")
            assertEquals(false, enableThinking)
            assertEquals(true, enableSearch)
        }
    }

    @Nested
    @DisplayName("DashScopeChatModelConfig - 默认值")
    inner class ConfigDefaultsTests {

        @Test
        fun `should have sensible defaults`() {
            val config = DashScopeChatModelConfig(modelName = TEST_MODEL, apiKey = TEST_API_KEY)

            assertTrue(config.stream)
            assertTrue(config.enableThinking)
            assertFalse(config.enableSearch)
            assertNull(config.baseUrl)
            assertNull(config.httpTransport)
            assertNull(config.options)
            assertFalse(config.encrypt)
            assertFalse(config.forceMultimodalEndpoint)
        }
    }

    @Nested
    @DisplayName("端点选择 - url error 回归")
    inner class EndpointSelectionTests {

        private val httpClient = DashScopeHttpClient(TEST_API_KEY)

        @Test
        fun `qwen3_7-max is excluded from SDK multimodal auto-detection`() {
            // SDK 2.0.2 显式将 qwen3.7-max 排除在多模态自动识别之外（走 text-generation），
            // 但 DashScope 服务端要求该模型走 multimodal-generation，否则报 url error，
            // 因此必须依赖 forceMultimodalEndpoint 补偿（见下方 ForceMultimodalTests）
            assertFalse(DashScopeHttpClient.isMultimodalModel("qwen3.7-max"))
            assertFalse(DashScopeHttpClient.isMultimodalModel("qwen3.7-max-2026-06-08"))
        }

        @Test
        fun `text-only models should not be detected as multimodal`() {
            assertFalse(DashScopeHttpClient.isMultimodalModel("qwen3-max"))
            assertFalse(DashScopeHttpClient.isMultimodalModel("qwen-plus"))
        }

        @Test
        fun `vision models should be detected as multimodal`() {
            assertTrue(DashScopeHttpClient.isMultimodalModel("qwen3-vl-plus"))
            assertTrue(DashScopeHttpClient.isMultimodalModel("qvq-max"))
        }

        @Test
        fun `auto detection selects text endpoint for qwen3_7-max proving the bug`() {
            val endpoint = httpClient.selectEndpoint("qwen3.7-max-2026-06-08")

            assertEquals(
                DashScopeHttpClient.TEXT_GENERATION_ENDPOINT,
                endpoint,
                "SDK 自动识别会把 qwen3.7-max 错误地路由到 text-generation 端点",
            )
        }

        @Test
        fun `explicit multimodal type selects multimodal endpoint`() {
            val endpoint = httpClient.selectEndpoint("qwen3.7-max-2026-06-08", EndpointType.MULTIMODAL)

            assertEquals(
                DashScopeHttpClient.MULTIMODAL_GENERATION_ENDPOINT,
                endpoint,
                "显式指定 MULTIMODAL 时必须走 multimodal-generation 端点",
            )
        }

        @Test
        fun `text-only model should select text generation endpoint`() {
            val endpoint = httpClient.selectEndpoint("qwen3-max")

            assertEquals(DashScopeHttpClient.TEXT_GENERATION_ENDPOINT, endpoint)
        }
    }

    @Nested
    @DisplayName("forceMultimodalEndpoint - 强制多模态端点")
    inner class ForceMultimodalTests {

        @Test
        fun `should not force multimodal endpoint by default`() {
            val model = ModelHelper.createChatModel(dashScopeConfig()) as DashScopeChatModel

            val endpointType: EndpointType? = readField(model, "endpointType")
            assertNotEquals(EndpointType.MULTIMODAL, endpointType, "默认不应强制多模态，由 SDK 自动识别")
        }

        @Test
        fun `should force MULTIMODAL endpoint when flag enabled`() {
            val model = ModelHelper.createChatModel(
                dashScopeConfig(forceMultimodalEndpoint = true),
            ) as DashScopeChatModel

            val endpointType: EndpointType? = readField(model, "endpointType")
            assertEquals(EndpointType.MULTIMODAL, endpointType, "support_vision 模型应强制多模态端点")
        }
    }

    @Nested
    @DisplayName("createChatModel - 类型分发")
    inner class DispatchTests {

        @Test
        fun `should build correct model type per config`() {
            val dashScope: ChatModelBase = ModelHelper.createChatModel(dashScopeConfig())
            assertTrue(dashScope is DashScopeChatModel)
        }
    }
}
