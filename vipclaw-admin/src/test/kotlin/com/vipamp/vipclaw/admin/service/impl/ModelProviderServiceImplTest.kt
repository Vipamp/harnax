package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest
import com.vipamp.vipclaw.admin.entity.ModelProvider
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.ModelMapper
import com.vipamp.vipclaw.admin.mapper.ModelProviderMapper
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.common.page.Page
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * ModelProviderServiceImpl 单元测试
 * 使用 Mockito 进行 Mock 测试
 *
 * @author vipamp
 * @since 2026-04-22
 */
class ModelProviderServiceImplTest {

    private lateinit var modelProviderMapper: ModelProviderMapper
    private lateinit var modelMapper: ModelMapper
    private lateinit var jwtUtil: JwtUtil
    private lateinit var service: ModelProviderServiceImpl

    @BeforeEach
    fun setUp() {
        modelProviderMapper = mock()
        modelMapper = mock()
        jwtUtil = mock()
        service = ModelProviderServiceImpl(modelMapper, jwtUtil, modelProviderMapper)
        
        // 设置 mock HTTP 请求上下文
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))
        
        // Mock JWT 验证和用户名获取
        whenever(jwtUtil.validateToken("mock-token")).thenReturn(true)
        whenever(jwtUtil.getUsernameFromToken("mock-token")).thenReturn("admin")
    }
    
    @AfterEach
    fun tearDown() {
        // 清理请求上下文
        RequestContextHolder.resetRequestAttributes()
    }

    @Nested
    @DisplayName("创建供应商测试")
    inner class CreateTests {
    
        @Test
        @DisplayName("create - 创建成功")
        fun `create should create provider successfully`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "dashscope",
                displayName = "阿里云百炼",
                apiKey = "sk-test-key-12345",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                isPublic = 1
            )
    
            whenever(modelProviderMapper.countByName("dashscope")).thenReturn(0)
            whenever(modelProviderMapper.insert(any())).thenReturn(1)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.create(request)
    
            // Then
            assertNotNull(result)
            assertEquals("dashscope", result.name)
            assertEquals("阿里云百炼", result.displayName)
            assertEquals("sk****2345", result.apiKey) // 脱敏:sk-test-key-12345 → sk****2345
            assertEquals(1, result.isPublic)
            assertEquals("admin", result.creator)
            assertEquals(1, result.status) // 默认启用
            assertEquals(1, result.active) // 默认正常
            assertNotNull(result.createTime)
            assertNotNull(result.updateTime)
    
            verify(modelProviderMapper).countByName("dashscope")
            verify(modelProviderMapper).insert(any())
        }
    
        @Test
        @DisplayName("create - 名称已存在应该抛出异常")
        fun `create should throw BizException when name exists`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "dashscope",
                displayName = "阿里云百炼"
            )
    
            whenever(modelProviderMapper.countByName("dashscope")).thenReturn(1)
    
            // When & Then
            val exception = assertThrows<BizException> {
                service.create(request)
            }
            assertEquals("供应商名称已存在", exception.message)
    
            verify(modelProviderMapper).countByName("dashscope")
            verify(modelProviderMapper, never()).insert(any())
        }
    
        @Test
        @DisplayName("create - isPublic 默认为 1")
        fun `create should default isPublic to 1`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "openai",
                displayName = "OpenAI"
            )
    
            whenever(modelProviderMapper.countByName("openai")).thenReturn(0)
            whenever(modelProviderMapper.insert(any())).thenReturn(1)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.create(request)
    
            // Then
            assertEquals(1, result.isPublic)
        }
    
        @Test
        @DisplayName("create - status 默认为 1(启用)")
        fun `create should default status to 1`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "anthropic",
                displayName = "Anthropic"
            )
    
            whenever(modelProviderMapper.countByName("anthropic")).thenReturn(0)
            whenever(modelProviderMapper.insert(any())).thenReturn(1)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.create(request)
    
            // Then
            assertEquals(1, result.status) // 应该默认为启用状态
        }
    
        @Test
        @DisplayName("create - apiKey 和 baseUrl 为 null 时保持 null")
        fun `create should keep apiKey and baseUrl as null when null`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "ollama",
                displayName = "本地模型"
            )
    
            whenever(modelProviderMapper.countByName("ollama")).thenReturn(0)
            whenever(modelProviderMapper.insert(any())).thenReturn(1)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.create(request)
    
            // Then
            assertNull(result.apiKey)
            assertNull(result.baseUrl)
        }
    
        @Test
        @DisplayName("create - 创建私有供应商")
        fun `create should create private provider when isPublic is 0`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "private-provider",
                displayName = "私有供应商",
                isPublic = 0
            )
    
            whenever(modelProviderMapper.countByName("private-provider")).thenReturn(0)
            whenever(modelProviderMapper.insert(any())).thenReturn(1)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.create(request)
    
            // Then
            assertEquals(0, result.isPublic)
        }
    }

    @Nested
    @DisplayName("更新供应商测试")
    inner class UpdateTests {

        @Test
        @DisplayName("update - 更新部分字段成功")
        fun `update should update partial fields successfully`() {
            // Given
            val id = 1L
            val existingProvider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.displayName = "阿里云百炼"
                this.apiKey = "sk-old-key"
                this.baseUrl = "https://old-url.com"
                this.isPublic = 1
                this.status = 1
                this.active = 1
                this.creator = "admin"
                this.createTime = LocalDateTime.now()
                this.updateTime = LocalDateTime.now()
            }

            val request = ModelProviderUpdateRequest(
                id = id,
                displayName = "更新后的显示名称",
                baseUrl = "https://new-url.com"
            )

            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(existingProvider)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)

            // When
            val result = service.update(id, request)

            // Then
            assertNotNull(result)
            assertEquals("更新后的显示名称", result.displayName)
            assertEquals("https://new-url.com", result.baseUrl)
            assertEquals("dashscope", result.name) // name 未更新
            assertEquals("sk****-key", result.apiKey) // apiKey 未更新，保持脱敏（sk-old-key → sk****-key）

            verify(modelProviderMapper).selectActiveById(id)
            verify(modelProviderMapper).updateById(any())
        }

        @Test
        @DisplayName("update - 供应商不存在应该抛出异常")
        fun `update should throw BizException when provider not found`() {
            // Given
            val id = 999L
            val request = ModelProviderUpdateRequest(
                id = id,
                displayName = "新名称"
            )

            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                service.update(id, request)
            }
            assertEquals("供应商不存在", exception.message)

            verify(modelProviderMapper).selectActiveById(id)
            verify(modelProviderMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("update - 更新名称时检查唯一性")
        fun `update should check name uniqueness when updating name`() {
            // Given
            val id = 1L
            val existingProvider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.displayName = "阿里云百炼"
                this.active = 1
            }

            val request = ModelProviderUpdateRequest(
                id = id,
                name = "openai" // 尝试更新为已存在的名称
            )

            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(existingProvider)
            whenever(modelProviderMapper.countByName("openai")).thenReturn(1)

            // When & Then
            val exception = assertThrows<BizException> {
                service.update(id, request)
            }
            assertEquals("供应商名称已存在", exception.message)

            verify(modelProviderMapper).selectActiveById(id)
            verify(modelProviderMapper).countByName("openai")
            verify(modelProviderMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("update - 更新 isPublic 字段")
        fun `update should update isPublic field`() {
            // Given
            val id = 1L
            val existingProvider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.displayName = "阿里云百炼"
                this.isPublic = 1
                this.active = 1
            }

            val request = ModelProviderUpdateRequest(
                id = id,
                isPublic = 0 // 改为私有
            )

            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(existingProvider)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)

            // When
            val result = service.update(id, request)

            // Then
            assertEquals(0, result.isPublic)
            verify(modelProviderMapper).updateById(any())
        }

        @Test
        @DisplayName("update - 空字符串 apiKey 不更新")
        fun `update should not update apiKey when empty string`() {
            // Given
            val id = 1L
            val existingProvider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.displayName = "阿里云百炼"
                this.apiKey = "sk-old-key"
                this.active = 1
            }

            val request = ModelProviderUpdateRequest(
                id = id,
                apiKey = "" // 空字符串
            )

            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(existingProvider)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)

            // When
            val result = service.update(id, request)

            // Then
            assertEquals("sk****-key", result.apiKey) // 应该保持旧的 apiKey（脱敏后：sk-old-key → sk****-key）
            verify(modelProviderMapper).updateById(any())
        }

        @Test
        @DisplayName("update - 纯空格 displayName 不更新")
        fun `update should not update displayName when blank string`() {
            // Given
            val id = 1L
            val existingProvider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.displayName = "阿里云百炼"
                this.active = 1
            }

            val request = ModelProviderUpdateRequest(
                id = id,
                displayName = "   " // 纯空格
            )

            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(existingProvider)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)

            // When
            val result = service.update(id, request)

            // Then
            assertEquals("阿里云百炼", result.displayName) // 应该保持旧的 displayName
            verify(modelProviderMapper).updateById(any())
        }

        @Test
        @DisplayName("update - 纯空格 name 不更新")
        fun `update should not update name when blank string`() {
            // Given
            val id = 1L
            val existingProvider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.displayName = "阿里云百炼"
                this.active = 1
            }

            val request = ModelProviderUpdateRequest(
                id = id,
                name = "   " // 纯空格
            )

            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(existingProvider)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)

            // When
            val result = service.update(id, request)

            // Then
            assertEquals("dashscope", result.name) // 应该保持旧的 name
            verify(modelProviderMapper).updateById(any())
        }

        @Test
        @DisplayName("update - 更新名称为相同值不检查唯一性")
        fun `update should not check uniqueness when name unchanged`() {
            // Given
            val id = 1L
            val existingProvider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.displayName = "阿里云百炼"
                this.active = 1
            }
        
            val request = ModelProviderUpdateRequest(
                id = id,
                name = "dashscope" // 名称不变
            )
        
            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(existingProvider)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)
        
            // When
            val result = service.update(id, request)
        
            // Then
            assertEquals("dashscope", result.name)
            verify(modelProviderMapper, never()).countByName(any()) // 不应调用唯一性检查
            verify(modelProviderMapper).updateById(any())
        }
        
        @Test
        @DisplayName("update - 更新新的 apiKey 成功")
        fun `update should update new apiKey successfully`() {
            // Given
            val id = 1L
            val existingProvider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.displayName = "阿里云百炼"
                this.apiKey = "sk-old-key-12345"
                this.active = 1
            }
        
            val request = ModelProviderUpdateRequest(
                id = id,
                apiKey = "sk-new-key-67890" // 新的 apiKey
            )
        
            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(existingProvider)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)
        
            // When
            val result = service.update(id, request)
        
            // Then
            assertEquals("sk****7890", result.apiKey) // 新 apiKey 脱敏后
            verify(modelProviderMapper).updateById(any())
        }
        
        @Test
        @DisplayName("update - 更新 null 字段不影响原有值")
        fun `update should not affect existing values when updating null fields`() {
            // Given
            val id = 1L
            val existingProvider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.displayName = "阿里云百炼"
                this.apiKey = "sk-test-key"
                this.baseUrl = "https://old-url.com"
                this.isPublic = 1
                this.active = 1
            }
        
            val request = ModelProviderUpdateRequest(
                id = id
                // 所有字段都是 null,不更新
            )
        
            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(existingProvider)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)
        
            // When
            val result = service.update(id, request)
        
            // Then
            assertEquals("dashscope", result.name)
            assertEquals("阿里云百炼", result.displayName)
            assertEquals("sk****-key", result.apiKey) // 保持原有值并脱敏
            assertEquals("https://old-url.com", result.baseUrl)
            assertEquals(1, result.isPublic)
        }
    }

    @Nested
    @DisplayName("查询供应商列表测试")
    inner class PageTests {
    
        @Test
        @DisplayName("page - 分页查询成功")
        fun `page should return paginated results`() {
            // Given
            val page = Page<ModelProvider>(1, 10)
            val providers = listOf(
                ModelProvider().apply {
                    id = 1
                    name = "dashscope"
                    displayName = "阿里云百炼"
                    apiKey = "sk-test-api-key-12345"
                    isPublic = 1
                    creator = "admin"
                },
                ModelProvider().apply {
                    id = 2
                    name = "openai"
                    displayName = "OpenAI"
                    apiKey = "sk-openai-key-67890"
                    isPublic = 1
                    creator = "admin"
                }
            )
    
            whenever(modelProviderMapper.selectModelProviderList(null, null, null, "admin")).thenReturn(providers)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.page(page, null, null, null)
    
            // Then
            assertNotNull(result)
            assertEquals(2, result.total)
            assertEquals(2, result.records.size)
            assertEquals("sk****2345", result.records[0].apiKey) // 脱敏:sk-test-api-key-12345 → sk****2345
            assertEquals("sk****7890", result.records[1].apiKey) // 脱敏:sk-openai-key-67890 → sk****7890
        }
    
        @Test
        @DisplayName("page - 按名称模糊搜索")
        fun `page should filter by name`() {
            // Given
            val page = Page<ModelProvider>(1, 10)
            val providers = listOf(
                ModelProvider().apply {
                    id = 1
                    name = "dashscope"
                    displayName = "阿里云百炼"
                }
            )
    
            whenever(modelProviderMapper.selectModelProviderList("dash", null, null, "admin")).thenReturn(providers)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.page(page, "dash", null, null)
    
            // Then
            assertEquals(1, result.total)
            assertEquals("dashscope", result.records[0].name)
    
            verify(modelProviderMapper).selectModelProviderList("dash", null, null, "admin")
        }
    
        @Test
        @DisplayName("page - 按状态筛选")
        fun `page should filter by status`() {
            // Given
            val page = Page<ModelProvider>(1, 10)
            val providers = listOf(
                ModelProvider().apply {
                    id = 1
                    name = "dashscope"
                    status = 1
                }
            )
    
            whenever(modelProviderMapper.selectModelProviderList(null, 1, null, "admin")).thenReturn(providers)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.page(page, null, 1, null)
    
            // Then
            assertEquals(1, result.total)
            assertEquals(1, result.records[0].status)
    
            verify(modelProviderMapper).selectModelProviderList(null, 1, null, "admin")
        }
    
        @Test
        @DisplayName("page - 按公开状态筛选")
        fun `page should filter by isPublic`() {
            // Given
            val page = Page<ModelProvider>(1, 10)
            val providers = listOf(
                ModelProvider().apply {
                    id = 1
                    name = "dashscope"
                    isPublic = 1
                }
            )
    
            whenever(modelProviderMapper.selectModelProviderList(null, null, 1, "admin")).thenReturn(providers)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.page(page, null, null, 1)
    
            // Then
            assertEquals(1, result.total)
            assertEquals(1, result.records[0].isPublic)
    
            verify(modelProviderMapper).selectModelProviderList(null, null, 1, "admin")
        }
    
        @Test
        @DisplayName("page - 空结果分页")
        fun `page should handle empty results`() {
            // Given
            val page = Page<ModelProvider>(1, 10)
            val providers = emptyList<ModelProvider>()
    
            whenever(modelProviderMapper.selectModelProviderList(null, null, null, "admin")).thenReturn(providers)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.page(page, null, null, null)
    
            // Then
            assertNotNull(result)
            assertEquals(0, result.total)
            assertEquals(0, result.records.size)
        }
    
        @Test
        @DisplayName("page - 第二页分页")
        fun `page should return second page results`() {
            // Given
            val page = Page<ModelProvider>(2, 10)
            val providers = (1..15).map { i ->
                ModelProvider().apply {
                    id = i.toLong()
                    name = "provider-$i"
                    displayName = "供应商 $i"
                }
            }
    
            whenever(modelProviderMapper.selectModelProviderList(null, null, null, "admin")).thenReturn(providers)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.page(page, null, null, null)
    
            // Then
            assertNotNull(result)
            assertEquals(15, result.total)
            assertEquals(5, result.records.size) // 第二页应该有5条记录(11-15)
            assertEquals("provider-11", result.records[0].name)
        }
    
        @Test
        @DisplayName("page - apiKey 为 null 时不脱敏")
        fun `page should handle null apiKey`() {
            // Given
            val page = Page<ModelProvider>(1, 10)
            val providers = listOf(
                ModelProvider().apply {
                    id = 1
                    name = "ollama"
                    displayName = "本地模型"
                    apiKey = null
                }
            )
    
            whenever(modelProviderMapper.selectModelProviderList(null, null, null, "admin")).thenReturn(providers)
            whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    
            // When
            val result = service.page(page, null, null, null)
    
            // Then
            assertNull(result.records[0].apiKey)
        }
    }

    @Nested
    @DisplayName("查询供应商详情测试")
    inner class GetDetailTests {

        @Test
        @DisplayName("getDetail - 查询成功")
        fun `getDetail should return provider detail`() {
            // Given
            val id = 1L
            val provider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.displayName = "阿里云百炼"
                this.apiKey = "sk-test-key-12345"
                this.isPublic = 1
            }

            whenever(modelProviderMapper.selectById(id)).thenReturn(provider)

            // When
            val result = service.getDetail(id)

            // Then
            assertNotNull(result)
            assertEquals("dashscope", result.name)
            assertEquals("sk****2345", result.apiKey) // 脱敏：sk-test-key-12345 → sk****2345
        }

        @Test
        @DisplayName("getDetail - 供应商不存在应该抛出异常")
        fun `getDetail should throw BizException when provider not found`() {
            // Given
            val id = 999L
            whenever(modelProviderMapper.selectById(id)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                service.getDetail(id)
            }
            assertEquals("模型服务商不存在", exception.message)
        }
    }

    @Nested
    @DisplayName("切换供应商状态测试")
    inner class ToggleTests {
    
        @Test
        @DisplayName("toggle - 从启用切换到禁用")
        fun `toggle should switch from enabled to disabled`() {
            // Given
            val id = 1L
            val provider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.status = 1 // 启用
                this.active = 1
            }
    
            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(provider)
            whenever(modelMapper.countActiveModelsByProviderId(id)).thenReturn(0)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)
    
            // When
            val result = service.toggle(id)
    
            // Then
            assertEquals(0, result.status) // 应该变为禁用
            verify(modelProviderMapper).updateById(any())
        }
    
        @Test
        @DisplayName("toggle - 从禁用切换到启用")
        fun `toggle should switch from disabled to enabled`() {
            // Given
            val id = 1L
            val provider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.status = 0 // 禁用
                this.active = 1
            }
    
            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(provider)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)
    
            // When
            val result = service.toggle(id)
    
            // Then
            assertEquals(1, result.status) // 应该变为启用
        }
    
        @Test
        @DisplayName("toggle - 有启用模型时无法禁用")
        fun `toggle should throw BizException when has active models`() {
            // Given
            val id = 1L
            val provider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.status = 1 // 启用
                this.active = 1
            }
    
            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(provider)
            whenever(modelMapper.countActiveModelsByProviderId(id)).thenReturn(5) // 有5个启用模型
    
            // When & Then
            val exception = assertThrows<BizException> {
                service.toggle(id)
            }
            assertEquals("该服务商下有启用的模型,无法禁用", exception.message)
    
            verify(modelProviderMapper, never()).updateById(any())
        }
    
        @Test
        @DisplayName("toggle - 供应商不存在应该抛出异常")
        fun `toggle should throw BizException when provider not found`() {
            // Given
            val id = 999L
            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(null)
    
            // When & Then
            val exception = assertThrows<BizException> {
                service.toggle(id)
            }
            assertEquals("模型服务商不存在", exception.message)
    
            verify(modelProviderMapper, never()).updateById(any())
        }
    
        @Test
        @DisplayName("toggle - 禁用时没有模型可以正常禁用")
        fun `toggle should disable when no active models`() {
            // Given
            val id = 1L
            val provider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.status = 1 // 启用
                this.active = 1
            }
    
            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(provider)
            whenever(modelMapper.countActiveModelsByProviderId(id)).thenReturn(0) // 没有启用模型
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)
    
            // When
            val result = service.toggle(id)
    
            // Then
            assertEquals(0, result.status)
            verify(modelMapper).countActiveModelsByProviderId(id) // 应该检查模型
            verify(modelProviderMapper).updateById(any())
        }
    
        @Test
        @DisplayName("toggle - 启用时不检查模型数量")
        fun `toggle should not check models when enabling`() {
            // Given
            val id = 1L
            val provider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.status = 0 // 禁用
                this.active = 1
            }
    
            whenever(modelProviderMapper.selectActiveById(id)).thenReturn(provider)
            whenever(modelProviderMapper.updateById(any())).thenReturn(1)
    
            // When
            val result = service.toggle(id)
    
            // Then
            assertEquals(1, result.status)
            verify(modelMapper, never()).countActiveModelsByProviderId(any()) // 不应该检查模型
            verify(modelProviderMapper).updateById(any())
        }
    }

    @Nested
    @DisplayName("删除供应商测试")
    inner class DeleteTests {
    
        @Test
        @DisplayName("removeProviderById - 删除成功")
        fun `removeProviderById should delete successfully`() {
            // Given
            val id = 1L
            whenever(modelMapper.countActiveModelsByProviderId(id)).thenReturn(0)
            whenever(modelProviderMapper.deleteById(id)).thenReturn(1)
    
            // When
            val result = service.removeProviderById(id)
    
            // Then
            assertTrue(result)
            verify(modelProviderMapper).deleteById(id)
        }
    
        @Test
        @DisplayName("removeProviderById - 有启用模型时无法删除")
        fun `removeProviderById should throw BizException when has active models`() {
            // Given
            val id = 1L
            whenever(modelMapper.countActiveModelsByProviderId(id)).thenReturn(3)
    
            // When & Then
            val exception = assertThrows<BizException> {
                service.removeProviderById(id)
            }
            assertEquals("该服务商下有启用的模型,无法删除", exception.message)
    
            verify(modelProviderMapper, never()).deleteById(id)
        }
    
        @Test
        @DisplayName("removeProviderById - 删除失败返回 false")
        fun `removeProviderById should return false when delete fails`() {
            // Given
            val id = 1L
            whenever(modelMapper.countActiveModelsByProviderId(id)).thenReturn(0)
            whenever(modelProviderMapper.deleteById(id)).thenReturn(0) // 删除失败
    
            // When
            val result = service.removeProviderById(id)
    
            // Then
            assertFalse(result)
            verify(modelProviderMapper).deleteById(id)
        }
    }

    @Nested
    @DisplayName("连通性测试")
    inner class ConnectivityTestTests {

        @Test
        @DisplayName("connectivityTest - 测试成功")
        fun `connectivityTest should return true`() {
            // Given
            val id = 1L
            val provider = ModelProvider().apply {
                this.id = id
                this.name = "dashscope"
                this.apiKey = "sk-test-key"
                this.baseUrl = "https://dashscope.aliyuncs.com"
            }

            whenever(modelProviderMapper.selectById(id)).thenReturn(provider)

            // When
            val result = service.connectivityTest(id)

            // Then
            assertTrue(result) // 目前实现直接返回 true
        }

        @Test
        @DisplayName("connectivityTest - 供应商不存在应该抛出异常")
        fun `connectivityTest should throw BizException when provider not found`() {
            // Given
            val id = 999L
            whenever(modelProviderMapper.selectById(id)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                service.connectivityTest(id)
            }
            assertEquals("模型服务商不存在", exception.message)
        }
    }
}
