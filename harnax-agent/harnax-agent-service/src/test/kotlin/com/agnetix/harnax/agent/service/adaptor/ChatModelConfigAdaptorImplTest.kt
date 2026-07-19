package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.model.DashScopeChatModelConfig
import com.agnetix.harnax.agent.adaptor.model.OllamaChatModelConfig
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.ModelProvider
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.ModelConfigDto
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.ModelProviderMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class ChatModelConfigAdaptorImplTest {

    private lateinit var specContextHolder: AgentSpecContextHolder
    private lateinit var modelMapper: ModelMapper
    private lateinit var modelProviderMapper: ModelProviderMapper
    private lateinit var adaptor: ChatModelConfigAdaptorImpl

    @BeforeEach
    fun setUp() {
        specContextHolder = mock(AgentSpecContextHolder::class.java)
        modelMapper = mock(ModelMapper::class.java)
        modelProviderMapper = mock(ModelProviderMapper::class.java)
        adaptor = ChatModelConfigAdaptorImpl(specContextHolder, modelMapper, modelProviderMapper)
    }

    private fun stubContext(modelConfig: ModelConfigDto?) {
        val specInfo = AgentSpecInfoResponse(
            agentId = 1L,
            agentName = "Test",
            description = "",
            systemPrompt = "",
            modelId = modelConfig?.modelId ?: 1L,
            modelConfig = modelConfig,
        )
        `when`(specContextHolder.get()).thenReturn(specInfo)
    }

    private fun stubDbFallback(modelId: Long, providerType: String, apiKey: String = "test-key") {
        `when`(specContextHolder.get()).thenReturn(null)
        val model = Model().apply {
            id = modelId
            modelName = "db-model-$modelId"
            providerId = 100L
            modelType = "chat"
        }
        val provider = ModelProvider().apply {
            id = 100L
            type = providerType
            this.apiKey = apiKey
            baseUrl = "http://localhost:8080"
        }
        `when`(modelMapper.selectById(modelId)).thenReturn(model)
        `when`(modelProviderMapper.selectById(100L)).thenReturn(provider)
    }

    @Nested
    @DisplayName("Context-first path")
    inner class ContextFirstTests {

        @Test
        fun `getConfig should load DashScope from context`() {
            val dto = ModelConfigDto(
                modelId = 1L,
                modelName = "qwen-max",
                providerType = "dashscope",
                apiKey = "sk-dashscope-123",
                baseUrl = "https://dashscope.aliyuncs.com",
            )
            stubContext(dto)

            val result = adaptor.getConfig(1L)

            assertNotNull(result)
            assertTrue(result is DashScopeChatModelConfig)
            verify(modelMapper, never()).selectById(1L)
        }

        @Test
        fun `getConfig should load OpenAI from context`() {
            val dto = ModelConfigDto(
                modelId = 2L,
                modelName = "gpt-4o",
                providerType = "openai",
                apiKey = "sk-openai-456",
                baseUrl = "https://api.openai.com",
            )
            stubContext(dto)

            val result = adaptor.getConfig(2L)

            assertNotNull(result)
            assertTrue(result is OpenAIChatModelConfig)
            verify(modelMapper, never()).selectById(2L)
        }

        @Test
        fun `getConfig should load Ollama from context without apiKey`() {
            val dto = ModelConfigDto(
                modelId = 3L,
                modelName = "llama3",
                providerType = "ollama",
                baseUrl = "http://localhost:11434",
            )
            stubContext(dto)

            val result = adaptor.getConfig(3L)

            assertNotNull(result)
            assertTrue(result is OllamaChatModelConfig)
            verify(modelMapper, never()).selectById(3L)
        }

        @Test
        fun `getConfig should return null for unknown provider type from context`() {
            val dto = ModelConfigDto(
                modelId = 4L,
                modelName = "unknown-model",
                providerType = "anthropic",
                apiKey = "sk-xxx",
            )
            stubContext(dto)

            val result = adaptor.getConfig(4L)

            assertNull(result)
        }

        @Test
        fun `getConfig should fallback to DB when context modelId does not match`() {
            val dto = ModelConfigDto(
                modelId = 1L,
                modelName = "qwen-max",
                providerType = "dashscope",
                apiKey = "sk-123",
            )
            stubContext(dto)
            // Request for modelId=999, context has modelId=1 → mismatch → fallback
            stubDbFallback(999L, "openai")

            val result = adaptor.getConfig(999L)

            assertNotNull(result)
            assertTrue(result is OpenAIChatModelConfig)
            verify(modelMapper).selectById(999L)
        }

        @Test
        fun `getConfig should fallback to DB when context is null`() {
            stubDbFallback(1L, "dashscope")

            val result = adaptor.getConfig(1L)

            assertNotNull(result)
            assertTrue(result is DashScopeChatModelConfig)
            verify(modelMapper).selectById(1L)
        }
    }

    @Nested
    @DisplayName("DB fallback path")
    inner class DbFallbackTests {

        @Test
        fun `getConfig should return Ollama from DB fallback`() {
            stubDbFallback(5L, "ollama")

            val result = adaptor.getConfig(5L)

            assertNotNull(result)
            assertTrue(result is OllamaChatModelConfig)
        }

        @Test
        fun `getConfig should return null when model not found in DB`() {
            `when`(specContextHolder.get()).thenReturn(null)
            `when`(modelMapper.selectById(999L)).thenReturn(null)

            val result = adaptor.getConfig(999L)

            assertNull(result)
        }

        @Test
        fun `getConfig should return null when provider not found in DB`() {
            `when`(specContextHolder.get()).thenReturn(null)
            val model = Model().apply {
                id = 1L
                modelName = "orphan-model"
                providerId = 999L
            }
            `when`(modelMapper.selectById(1L)).thenReturn(model)
            `when`(modelProviderMapper.selectById(999L)).thenReturn(null)

            val result = adaptor.getConfig(1L)

            assertNull(result)
        }

        @Test
        fun `getConfig should return null for unknown provider type from DB`() {
            stubDbFallback(1L, "gemini")

            val result = adaptor.getConfig(1L)

            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Basic validation")
    inner class BasicValidation {

        @Test
        fun `getConfig should return null for invalid modelId zero`() {
            val result = adaptor.getConfig(0)
            assertNull(result)
        }

        @Test
        fun `getConfig should return null for negative modelId`() {
            val result = adaptor.getConfig(-1)
            assertNull(result)
        }
    }
}
