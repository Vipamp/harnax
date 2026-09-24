package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.EnvVariableCreateRequest
import com.agnetix.harnax.admin.dto.EnvVariableUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.EnvVariable
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.EnvVariableMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

/**
 * EnvVariableServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper and utility layer dependencies
 * Covers normal flows, exception flows, and boundary conditions
 *
 * @author agnetix
 * @since 2026-05-17
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvVariableServiceImplTest {

    @Mock
    private lateinit var envVariableMapper: EnvVariableMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var aesUtil: AesUtil

    @Mock
    private lateinit var agentMapper: AgentMapper

    private lateinit var testEnvVariable: EnvVariable

    @BeforeEach
    fun setUp() {
        testEnvVariable = EnvVariable().apply {
            id = 1L
            tenantId = 1L
            envKey = "API_KEY"
            envValue = "sk-plain-value"
            description = "Test env variable"
            sensitive = 0
            enabled = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        // Mock HttpServletRequest for UserContextUtil
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))

        // Mock JwtUtil
        `when`(jwtUtil.validateToken(anyString())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(anyString())).thenReturn("admin")
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        RequestContextHolder.resetRequestAttributes()
    }

    private fun createService(): EnvVariableServiceImpl = EnvVariableServiceImpl(
        envVariableMapper = envVariableMapper,
        jwtUtil = jwtUtil,
        aesUtil = aesUtil,
        agentMapper = agentMapper,
    )

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Normal pagination query")
        fun `page should return paginated results`() {
            // Given
            `when`(envVariableMapper.selectEnvVariableList(null, "admin", 1L)).thenReturn(listOf(testEnvVariable))

            // When
            val page = createService().page(null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(envVariableMapper).selectEnvVariableList(null, "admin", 1L)
        }

        @Test
        @DisplayName("page - Filter by keyword")
        fun `page should filter by keyword`() {
            // Given
            `when`(envVariableMapper.selectEnvVariableList("API", "admin", 1L)).thenReturn(listOf(testEnvVariable))

            // When
            val page = createService().page("API", 1, 10)

            // Then
            assertNotNull(page)
            verify(envVariableMapper).selectEnvVariableList("API", "admin", 1L)
        }

        @Test
        @DisplayName("page - Bound invalid pageNum and pageSize")
        fun `page should bound invalid pageNum and pageSize`() {
            // Given - pageNum < 1 is coerced to 1 and pageSize is coerced into 1..1000
            `when`(envVariableMapper.selectEnvVariableList(null, "admin", 1L)).thenReturn(emptyList())

            // When
            val page = createService().page(null, 0, 10000)

            // Then
            assertNotNull(page)
            verify(envVariableMapper).selectEnvVariableList(null, "admin", 1L)
        }
    }

    @Nested
    @DisplayName("Get Env Variable Tests")
    inner class GetEnvVariableTests {

        @Test
        @DisplayName("getEnvVariable - Query by ID successfully")
        fun `getEnvVariable should return env variable by id`() {
            // Given
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)

            // When
            val result = createService().getEnvVariable(1L)

            // Then
            assertNotNull(result)
            assertEquals("API_KEY", result?.envKey)
            verify(envVariableMapper).selectById(1L)
        }

        @Test
        @DisplayName("getEnvVariable - Return null when not exists")
        fun `getEnvVariable should return null when not exists`() {
            // Given
            `when`(envVariableMapper.selectById(999L)).thenReturn(null)

            // When
            val result = createService().getEnvVariable(999L)

            // Then
            assertNull(result)
            verify(envVariableMapper).selectById(999L)
        }

        @Test
        @DisplayName("getEnvVariable - Return null for another tenant's row")
        fun `getEnvVariable should return null for another tenant row`() {
            // Given - 详情接口把非敏感值原样带回，只按列表过滤挡不住按 id 枚举
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(2L)).thenReturn(
                EnvVariable().apply {
                    id = 2L
                    tenantId = 2L
                    envKey = "OTHER_TENANT_KEY"
                    envValue = "their-plain-value"
                    creator = "someone-else"
                },
            )

            // When
            val result = createService().getEnvVariable(2L)

            // Then - 跨租户与查不到给同一个答复，不暴露「这个 id 存在」
            assertNull(result)
        }

        @Test
        @DisplayName("getEnvVariable - Another user's row of the same tenant reads as absent")
        fun `getEnvVariable should return null for another user row of the same tenant`() {
            // 列表与下拉本来就只给调用者自己建的行，按 id 直读是唯一还跨得过这条线的地方：
            // 非敏感值是原样回显的，同租户的同事猜到 id 就读走了名字和值。
            // 隔离单位收成创建人，与「列表只显示当前用户的环境变量」是同一条口径。
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(3L)).thenReturn(
                EnvVariable().apply {
                    id = 3L
                    tenantId = 1L
                    envKey = "CO_WORKER_KEY"
                    envValue = "their-plain-value"
                    creator = "co-worker"
                    active = 1
                },
            )

            assertNull(createService().getEnvVariable(3L))
        }

        @Test
        @DisplayName("getRowWithinTenant - Another user's row still resolves for a binding")
        fun `getRowWithinTenant should return another user row of the same tenant`() {
            // 这一半是同一条隔离收口的代价：智能体按 id 存引用，共享智能体绑的是它属主建的行，
            // 而编辑那个智能体的人不是属主。绑定的回填与保存判据因此按租户，不按创建人——
            // 运行下发（getDecryptedValue）也是按租户，两侧同口径才不会出现「能跑不能存」。
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(3L)).thenReturn(
                EnvVariable().apply {
                    id = 3L
                    tenantId = 1L
                    envKey = "CO_WORKER_KEY"
                    envValue = "their-plain-value"
                    creator = "co-worker"
                    active = 1
                },
            )

            assertEquals("CO_WORKER_KEY", createService().getRowWithinTenant(3L)?.envKey)
            // 租户这一半照旧守住：桩一行别人租户的，缺席的答复必须仍然按租户给
            `when`(envVariableMapper.selectById(2L)).thenReturn(
                EnvVariable().apply {
                    id = 2L
                    tenantId = 2L
                    envKey = "OTHER_TENANT_KEY"
                    creator = "co-worker"
                    active = 1
                },
            )
            assertNull(createService().getRowWithinTenant(2L))
        }
    }

    @Nested
    @DisplayName("Create Env Variable Tests")
    inner class CreateEnvVariableTests {

        @Test
        @DisplayName("createEnvVariable - Create non-sensitive variable successfully")
        fun `createEnvVariable should create non-sensitive variable successfully`() {
            // Given
            val request = EnvVariableCreateRequest(
                envKey = "NEW_KEY",
                envValue = "new-value",
                description = "New variable",
                sensitive = 0,
                enabled = 1,
            )

            `when`(envVariableMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createEnvVariable(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("NEW_KEY", saved.envKey)
            assertEquals("new-value", saved.envValue)
            assertEquals(0, saved.sensitive)
            assertEquals("admin", saved.creator)
            // Non-sensitive value is not encrypted
            verify(aesUtil, never()).encrypt(anyString())
        }

        @Test
        @DisplayName("createEnvVariable - Encrypt value when sensitive")
        fun `createEnvVariable should encrypt value when sensitive`() {
            // Given
            val request = EnvVariableCreateRequest(
                envKey = "SECRET_KEY",
                envValue = "raw-secret",
                sensitive = 1,
            )

            `when`(aesUtil.encrypt("raw-secret")).thenReturn("encrypted-secret")
            `when`(envVariableMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createEnvVariable(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).insert(captor.capture())
            assertEquals("encrypted-secret", captor.firstValue.envValue)
            verify(aesUtil).encrypt("raw-secret")
        }

        @Test
        @DisplayName("createEnvVariable - Set default sensitive and enabled when not provided")
        fun `createEnvVariable should set default sensitive and enabled when not provided`() {
            // Given
            val request = EnvVariableCreateRequest(
                envKey = "DEFAULT_KEY",
                envValue = "default-value",
            )

            `when`(envVariableMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createEnvVariable(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).insert(captor.capture())
            assertEquals(0, captor.firstValue.sensitive)
            assertEquals(1, captor.firstValue.enabled)
        }

        @Test
        @DisplayName("createEnvVariable - Set tenantId from TenantContext")
        fun `createEnvVariable should set tenantId from TenantContext`() {
            // Given
            TenantContext.setTenantId(7L)
            val request = EnvVariableCreateRequest(
                envKey = "TENANT_KEY",
                envValue = "tenant-value",
            )

            `when`(envVariableMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createEnvVariable(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).insert(captor.capture())
            assertEquals(7L, captor.firstValue.tenantId)
        }

        @Test
        @DisplayName("createEnvVariable - Throw RuntimeException when envKey missing")
        fun `createEnvVariable should throw RuntimeException when envKey missing`() {
            // Given - envKey is null, request.envKey!! throws NPE which is wrapped
            val request = EnvVariableCreateRequest(
                envKey = null,
                envValue = "value",
            )

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createEnvVariable(request)
            }
            assertEquals("Failed to create env variable", exception.message)
            verify(envVariableMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createEnvVariable - Refuse a key the caller already holds, naming it")
        fun `createEnvVariable should refuse a key the caller already holds`() {
            // uk_env_tenant_creator_active_key 会把这撞成一句 SQL 错误，服务层先给可读的拒绝；
            // 而 AGENT-23 的旧形状是连这句拒绝都被控制器的兜底串吞掉，只回 "Failed to create env variable"
            val clash = EnvVariable().apply {
                id = 1L
                tenantId = 1L
                envKey = "API_KEY"
                creator = "admin"
                active = 1
            }
            `when`(envVariableMapper.selectByKey("API_KEY", "admin", 1L)).thenReturn(clash)

            val request = EnvVariableCreateRequest(envKey = "API_KEY", envValue = "another-value")
            val exception = assertThrows<BizException> { createService().createEnvVariable(request) }

            assertTrue(exception.message!!.contains("API_KEY"), "the refusal should name the key, got: ${exception.message}")
            verify(envVariableMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createEnvVariable - A key another user holds stays free for this caller")
        fun `createEnvVariable should accept a key another user holds`() {
            // 按用户唯一的全部含义：同租户同事占了这个名字不再挡住这里。这条用例在只有租户条件时
            // 也会绿，所以判据落在探测语句被问的是谁——按调用人，不按租户
            `when`(envVariableMapper.selectByKey("API_KEY", "admin", 1L)).thenReturn(null)
            `when`(envVariableMapper.insert(any())).thenReturn(1)

            val result = createService().createEnvVariable(EnvVariableCreateRequest(envKey = "API_KEY", envValue = "v"))

            assertTrue(result)
            verify(envVariableMapper).selectByKey("API_KEY", "admin", 1L)
        }

        @Test
        @DisplayName("createEnvVariable - Throw RuntimeException when insert fails")
        fun `createEnvVariable should throw RuntimeException when insert fails`() {
            // Given
            val request = EnvVariableCreateRequest(
                envKey = "FAIL_KEY",
                envValue = "fail-value",
            )

            `when`(envVariableMapper.insert(any())).thenThrow(RuntimeException("db error"))

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createEnvVariable(request)
            }
            assertEquals("Failed to create env variable", exception.message)
        }
    }

    @Nested
    @DisplayName("Update Env Variable Tests")
    inner class UpdateEnvVariableTests {

        @Test
        @DisplayName("updateEnvVariable - Update non-sensitive value successfully")
        fun `updateEnvVariable should update non-sensitive value successfully`() {
            // Given
            TenantContext.setTenantId(1L)
            val request = EnvVariableUpdateRequest(
                envKey = "API_KEY_V2",
                envValue = "updated-value",
                description = "Updated description",
            )

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateEnvVariable(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            val updated = captor.firstValue
            assertEquals("API_KEY_V2", updated.envKey)
            assertEquals("updated-value", updated.envValue)
            assertEquals("Updated description", updated.description)
            verify(aesUtil, never()).encrypt(anyString())
        }

        @Test
        @DisplayName("updateEnvVariable - Refuse a rename onto a key the caller already holds")
        fun `updateEnvVariable should refuse a rename onto a key the caller already holds`() {
            // 改名也是写入，撞的还是 uk_env_tenant_creator_active_key。旧形状里这句 SQL 错误
            // 被服务的兜底串包成 "Failed to update env variable"，调用方看不出是哪个键
            TenantContext.setTenantId(1L)
            val held = EnvVariable().apply {
                id = 5L
                tenantId = 1L
                envKey = "TAKEN"
                creator = "admin"
                active = 1
            }
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.selectByKey("TAKEN", "admin", 1L)).thenReturn(held)

            val exception = assertThrows<BizException> {
                createService().updateEnvVariable(1L, EnvVariableUpdateRequest(envKey = "TAKEN"))
            }

            assertTrue(exception.message!!.contains("TAKEN"), "the refusal should name the key, got: ${exception.message}")
            verify(envVariableMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateEnvVariable - The row's own key is not a clash")
        fun `updateEnvVariable should not treat the stored key as a clash`() {
            // 这一行自己占着那个名字：按名字查重而不排除自己，会把每一次"改值不改名"的保存撞死
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            val result = createService().updateEnvVariable(1L, EnvVariableUpdateRequest(envKey = "API_KEY", description = "kept key"))

            assertTrue(result)
            verify(envVariableMapper, never()).selectByKey(anyString(), anyString(), anyLong())
        }

        @Test
        @DisplayName("updateEnvVariable - Encrypt new value when target sensitive is 1")
        fun `updateEnvVariable should encrypt new value when target sensitive is 1`() {
            // Given
            TenantContext.setTenantId(1L)
            val request = EnvVariableUpdateRequest(
                envValue = "new-secret",
                sensitive = 1,
            )

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(aesUtil.encrypt("new-secret")).thenReturn("encrypted-new-secret")
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateEnvVariable(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            assertEquals("encrypted-new-secret", captor.firstValue.envValue)
            assertEquals(1, captor.firstValue.sensitive)
            verify(aesUtil).encrypt("new-secret")
        }

        @Test
        @DisplayName("updateEnvVariable - A masked sensitive value keeps the stored ciphertext")
        fun `updateEnvVariable should keep stored ciphertext for masked value`() {
            // Given: the row already holds ciphertext and the page sends back what the detail API masked
            testEnvVariable.sensitive = 1
            testEnvVariable.envValue = "encrypted-stored-value"
            `when`(aesUtil.decrypt("encrypted-stored-value")).thenReturn("sk-live-value")
            val request = EnvVariableUpdateRequest(envValue = "sk-****ue")

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateEnvVariable(1L, request)

            // Then: encrypting the mask would store the mask in place of the credential
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            assertEquals("encrypted-stored-value", captor.firstValue.envValue)
            verify(aesUtil, never()).encrypt(anyString())
        }

        @Test
        @DisplayName("updateEnvVariable - A real value that contains asterisks is stored")
        fun `updateEnvVariable should store a new value that contains the mask pattern`() {
            // Given: the mask is compared against, not searched for — a credential typed with "****" in
            // it used to be read as "unchanged", so the call answered success and kept the old secret
            testEnvVariable.sensitive = 1
            testEnvVariable.envValue = "encrypted-stored-value"
            `when`(aesUtil.decrypt("encrypted-stored-value")).thenReturn("sk-live-value")
            val request = EnvVariableUpdateRequest(envValue = "secret****key")

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(aesUtil.encrypt("secret****key")).thenReturn("encrypted-asterisk-value")
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateEnvVariable(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            assertEquals("encrypted-asterisk-value", captor.firstValue.envValue)
        }

        @Test
        @DisplayName("updateEnvVariable - The full mask of a short stored value keeps the ciphertext")
        fun `updateEnvVariable should keep ciphertext for full mask of a short value`() {
            // Given: a four-character credential displays as the fixed mask rather than a reduced one
            testEnvVariable.sensitive = 1
            testEnvVariable.envValue = "encrypted-stored-value"
            `when`(aesUtil.decrypt("encrypted-stored-value")).thenReturn("abcd")
            val request = EnvVariableUpdateRequest(envValue = "******")

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateEnvVariable(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            assertEquals("encrypted-stored-value", captor.firstValue.envValue)
            verify(aesUtil, never()).encrypt(anyString())
        }

        @Test
        @DisplayName("updateEnvVariable - Keep current value when envValue not provided")
        fun `updateEnvVariable should keep current value when envValue not provided`() {
            // Given
            TenantContext.setTenantId(1L)
            val request = EnvVariableUpdateRequest(
                description = "Only description updated",
            )

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateEnvVariable(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            assertEquals("sk-plain-value", captor.firstValue.envValue)
        }

        @Test
        @DisplayName("updateEnvVariable - 取消敏感标记时把密文还原成明文")
        fun `updateEnvVariable should decrypt when sensitive flips to zero`() {
            // Given - 只翻标志：这一列仍是密文，下发时会被当明文交给工具
            TenantContext.setTenantId(1L)
            testEnvVariable.sensitive = 1
            testEnvVariable.envValue = "encrypted-stored-value"
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(aesUtil.decrypt("encrypted-stored-value")).thenReturn("the-real-value")
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            createService().updateEnvVariable(1L, EnvVariableUpdateRequest(sensitive = 0))

            // Then
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            assertEquals("the-real-value", captor.firstValue.envValue)
            assertEquals(0, captor.firstValue.sensitive)
        }

        @Test
        @DisplayName("updateEnvVariable - 加上敏感标记时把明文编成密文")
        fun `updateEnvVariable should encrypt when sensitive flips to one`() {
            // Given - 反向同理：不重编的话，下一次按密文解理会失败，绑定静默拿不到值
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(aesUtil.encrypt("sk-plain-value")).thenReturn("encrypted-plain-value")
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            createService().updateEnvVariable(1L, EnvVariableUpdateRequest(sensitive = 1))

            // Then
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            assertEquals("encrypted-plain-value", captor.firstValue.envValue)
            assertEquals(1, captor.firstValue.sensitive)
        }

        @Test
        @DisplayName("updateEnvVariable - Refuse a missing row by name")
        fun `updateEnvVariable should refuse a missing row by name`() {
            // Given
            val request = EnvVariableUpdateRequest(description = "Update")

            `when`(envVariableMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateEnvVariable(999L, request)
            }
            // 不再被兜底 catch 折成「Failed to update」：调用方要分得清「这行不是你的」和「库坏了」
            assertEquals("Env variable not found", exception.message)
            verify(envVariableMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateEnvVariable - Refuse another user's row as absent")
        fun `updateEnvVariable should refuse another user row as absent`() {
            // Given
            TenantContext.setTenantId(1L)
            val otherUserEnv = EnvVariable().apply {
                id = 1L
                tenantId = 1L
                envKey = "OTHER_KEY"
                envValue = "other-value"
                creator = "otheruser"
            }
            val request = EnvVariableUpdateRequest(description = "IDOR attempt")

            `when`(envVariableMapper.selectById(1L)).thenReturn(otherUserEnv)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateEnvVariable(1L, request)
            }
            assertEquals("Env variable not found", exception.message)
            verify(envVariableMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateEnvVariable - Refuse another tenant's row as absent")
        fun `updateEnvVariable should refuse another tenant row as absent`() {
            // Given
            TenantContext.setTenantId(2L)
            val request = EnvVariableUpdateRequest(description = "Cross tenant attempt")

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable) // tenantId = 1L

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateEnvVariable(1L, request)
            }
            assertEquals("Env variable not found", exception.message)
            verify(envVariableMapper, never()).updateById(any())
        }
    }

    @Nested
    @DisplayName("Delete Env Variable Tests")
    inner class DeleteEnvVariableTests {

        @Test
        @DisplayName("deleteEnvVariable - Delete env variable successfully")
        fun `deleteEnvVariable should delete env variable successfully`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = createService().deleteEnvVariable(1L)

            // Then
            assertTrue(result)
            verify(envVariableMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteEnvVariable - Refuse to delete a variable an agent still binds")
        fun `deleteEnvVariable should refuse when an agent still references it`() {
            // Given: the binding stores only the id, so deleting the row would silently empty the agent
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(agentMapper.selectByEnvVarRef(1L)).thenReturn(
                listOf(
                    Agent().apply {
                        id = 11L
                        name = "customer-support"
                    },
                ),
            )

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().deleteEnvVariable(1L)
            }
            assertTrue(exception.message!!.contains("1 agent(s)"), "message should carry the count")
            assertTrue(exception.message!!.contains("customer-support"), "message should name the offender")
            verify(envVariableMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteEnvVariable - Throw RuntimeException when not found")
        fun `deleteEnvVariable should throw RuntimeException when not found`() {
            // Given
            `when`(envVariableMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().deleteEnvVariable(999L)
            }
            assertEquals("Env variable not found", exception.message)
            verify(envVariableMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteEnvVariable - Refuse another user's row as absent")
        fun `deleteEnvVariable should refuse another user row as absent`() {
            // Given - 手写 creator 比对已删：作用域在 getEnvVariable 里，别人的行到这里就是「不存在」
            TenantContext.setTenantId(1L)
            val otherUserEnv = EnvVariable().apply {
                id = 1L
                tenantId = 1L
                creator = "otheruser"
            }

            `when`(envVariableMapper.selectById(1L)).thenReturn(otherUserEnv)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().deleteEnvVariable(1L)
            }
            assertEquals("Env variable not found", exception.message)
            verify(envVariableMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteEnvVariable - Return false when delete affects no rows")
        fun `deleteEnvVariable should return false when delete affects no rows`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.deleteById(1L)).thenReturn(0)

            // When
            val result = createService().deleteEnvVariable(1L)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("Toggle Enabled Tests")
    inner class ToggleEnabledTests {

        @Test
        @DisplayName("toggleEnabled - Disable env variable successfully")
        fun `toggleEnabled should disable env variable successfully`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.toggleEnabled(1L, 0)).thenReturn(1)

            // When
            val result = createService().toggleEnabled(1L, 0)

            // Then
            assertTrue(result)
            verify(envVariableMapper).toggleEnabled(1L, 0)
        }

        @Test
        @DisplayName("toggleEnabled - Enable env variable successfully")
        fun `toggleEnabled should enable env variable successfully`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.toggleEnabled(1L, 1)).thenReturn(1)

            // When
            val result = createService().toggleEnabled(1L, 1)

            // Then
            assertTrue(result)
            verify(envVariableMapper).toggleEnabled(1L, 1)
        }

        @Test
        @DisplayName("toggleEnabled - Throw RuntimeException when not found")
        fun `toggleEnabled should throw RuntimeException when not found`() {
            // Given
            `when`(envVariableMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().toggleEnabled(999L, 1)
            }
            assertEquals("Env variable not found", exception.message)
            verify(envVariableMapper, never()).toggleEnabled(anyLong(), anyInt())
        }

        @Test
        @DisplayName("toggleEnabled - Refuse another user's row as absent")
        fun `toggleEnabled should refuse another user row as absent`() {
            // Given - 开关和删除走同一个作用域读法，别人的行到这里就是「不存在」
            TenantContext.setTenantId(1L)
            val otherUserEnv = EnvVariable().apply {
                id = 1L
                tenantId = 1L
                creator = "otheruser"
            }

            `when`(envVariableMapper.selectById(1L)).thenReturn(otherUserEnv)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().toggleEnabled(1L, 0)
            }
            assertEquals("Env variable not found", exception.message)
            verify(envVariableMapper, never()).toggleEnabled(anyLong(), anyInt())
        }

        @Test
        @DisplayName("toggleEnabled - Refuse to switch off a variable agents still bind")
        fun `toggleEnabled should refuse to disable a referenced variable`() {
            // 停用与删除一样会把值抽走：绑定里存的是 envVarId，每次下发都要现取
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(agentMapper.selectByEnvVarRef(1L)).thenReturn(
                listOf(
                    Agent().apply {
                        id = 11L
                        name = "customer-support"
                    },
                ),
            )

            val exception = assertThrows<RuntimeException> {
                createService().toggleEnabled(1L, 0)
            }

            val message = exception.message!!
            assertTrue(message.contains("Env variable 'API_KEY' is bound by 1 agent(s): customer-support"), message)
            assertTrue(message.contains("then disable"), message)
            verify(envVariableMapper, never()).toggleEnabled(anyLong(), anyInt())
        }

        @Test
        @DisplayName("toggleEnabled - Switching a referenced variable back on stays open")
        fun `toggleEnabled should allow re-enabling a referenced variable`() {
            // 被引用只挡住「把值抽走」的方向，重新启用是补回来
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(agentMapper.selectByEnvVarRef(1L)).thenReturn(
                listOf(
                    Agent().apply {
                        id = 11L
                        name = "customer-support"
                    },
                ),
            )
            `when`(envVariableMapper.toggleEnabled(1L, 1)).thenReturn(1)

            assertTrue(createService().toggleEnabled(1L, 1))

            verify(envVariableMapper).toggleEnabled(1L, 1)
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Return plain value for non-sensitive variable")
        fun `convertToResponse should return plain value for non-sensitive variable`() {
            // When
            val result = createService().convertToResponse(testEnvVariable)

            // Then
            assertNotNull(result)
            assertEquals(1L, result.id)
            assertEquals("API_KEY", result.envKey)
            assertEquals("sk-plain-value", result.envValue)
            assertEquals(0, result.sensitive)
        }

        @Test
        @DisplayName("convertToResponse - Mask decrypted value for sensitive variable")
        fun `convertToResponse should mask decrypted value for sensitive variable`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET"
                envValue = "encrypted-payload"
                sensitive = 1
            }

            `when`(aesUtil.decrypt("encrypted-payload")).thenReturn("sk-1234567890abcdef")

            // When
            val result = createService().convertToResponse(sensitiveEnv)

            // Then
            assertNotNull(result)
            // Long value: first 3 chars + **** + last 2 chars
            assertEquals("sk-****ef", result.envValue)
            verify(aesUtil).decrypt("encrypted-payload")
        }

        @Test
        @DisplayName("convertToResponse - Return mask placeholder when decryption fails")
        fun `convertToResponse should return mask placeholder when decryption fails`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET"
                envValue = "bad-payload"
                sensitive = 1
            }

            `when`(aesUtil.decrypt("bad-payload")).thenThrow(RuntimeException("decrypt error"))

            // When
            val result = createService().convertToResponse(sensitiveEnv)

            // Then
            assertNotNull(result)
            assertEquals("******", result.envValue)
        }

        @Test
        @DisplayName("convertToResponse - Fully mask short sensitive value")
        fun `convertToResponse should fully mask short sensitive value`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 3L
                envKey = "SHORT_SECRET"
                envValue = "encrypted-short"
                sensitive = 1
            }

            `when`(aesUtil.decrypt("encrypted-short")).thenReturn("abcd")

            // When
            val result = createService().convertToResponse(sensitiveEnv)

            // Then
            assertEquals("******", result.envValue)
        }
    }

    @Nested
    @DisplayName("List For Agent Config Tests")
    inner class ListForAgentConfigTests {

        @Test
        @DisplayName("listForAgentConfig - Return only enabled variables")
        fun `listForAgentConfig should return only enabled variables`() {
            // Given
            val disabledEnv = EnvVariable().apply {
                id = 2L
                envKey = "DISABLED_KEY"
                envValue = "disabled-value"
                enabled = 0
                creator = "admin"
            }

            `when`(envVariableMapper.selectEnvVariableList(null, "admin", 1L))
                .thenReturn(listOf(testEnvVariable, disabledEnv))

            // When
            val result = createService().listForAgentConfig()

            // Then
            assertEquals(1, result.size)
            assertEquals("API_KEY", result[0]["envKey"])
            assertEquals("sk-plain-value", result[0]["displayValue"])
            assertEquals(false, result[0]["sensitive"])
        }

        @Test
        @DisplayName("listForAgentConfig - Mask sensitive variable value")
        fun `listForAgentConfig should mask sensitive variable value`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET_KEY"
                envValue = "encrypted-payload"
                sensitive = 1
                enabled = 1
                creator = "admin"
            }

            `when`(envVariableMapper.selectEnvVariableList(null, "admin", 1L)).thenReturn(listOf(sensitiveEnv))
            `when`(aesUtil.decrypt("encrypted-payload")).thenReturn("sk-1234567890abcdef")

            // When
            val result = createService().listForAgentConfig()

            // Then
            assertEquals(1, result.size)
            assertEquals("sk-****ef", result[0]["displayValue"])
            assertEquals(true, result[0]["sensitive"])
        }

        @Test
        @DisplayName("listForAgentConfig - Return mask placeholder when decryption fails")
        fun `listForAgentConfig should return mask placeholder when decryption fails`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET_KEY"
                envValue = "bad-payload"
                sensitive = 1
                enabled = 1
                creator = "admin"
            }

            `when`(envVariableMapper.selectEnvVariableList(null, "admin", 1L)).thenReturn(listOf(sensitiveEnv))
            `when`(aesUtil.decrypt("bad-payload")).thenThrow(RuntimeException("decrypt error"))

            // When
            val result = createService().listForAgentConfig()

            // Then
            assertEquals(1, result.size)
            assertEquals("******", result[0]["displayValue"])
        }

        @Test
        @DisplayName("listForAgentConfig - Return empty list when no variables")
        fun `listForAgentConfig should return empty list when no variables`() {
            // Given
            `when`(envVariableMapper.selectEnvVariableList(null, "admin", 1L)).thenReturn(emptyList())

            // When
            val result = createService().listForAgentConfig()

            // Then
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    @DisplayName("Get Decrypted Value Tests")
    inner class GetDecryptedValueTests {

        @Test
        @DisplayName("getDecryptedValue - Return decrypted value for sensitive variable")
        fun `getDecryptedValue should return decrypted value for sensitive variable`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET"
                envValue = "encrypted-payload"
                sensitive = 1
            }

            `when`(envVariableMapper.selectById(2L)).thenReturn(sensitiveEnv)
            `when`(aesUtil.decrypt("encrypted-payload")).thenReturn("real-secret")

            // When
            val result = createService().getDecryptedValue(2L, 1L)

            // Then
            assertEquals("real-secret", result)
            verify(aesUtil).decrypt("encrypted-payload")
        }

        @Test
        @DisplayName("getDecryptedValue - Return plain value for non-sensitive variable")
        fun `getDecryptedValue should return plain value for non-sensitive variable`() {
            // Given
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)

            // When
            val result = createService().getDecryptedValue(1L, 1L)

            // Then
            assertEquals("sk-plain-value", result)
            verify(aesUtil, never()).decrypt(anyString())
        }

        @Test
        @DisplayName("getDecryptedValue - Return null when variable not found")
        fun `getDecryptedValue should return null when variable not found`() {
            // Given
            `when`(envVariableMapper.selectById(999L)).thenReturn(null)

            // When
            val result = createService().getDecryptedValue(999L, 1L)

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("getDecryptedValue - Return null when decryption fails")
        fun `getDecryptedValue should return null when decryption fails`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET"
                envValue = "bad-payload"
                sensitive = 1
            }

            `when`(envVariableMapper.selectById(2L)).thenReturn(sensitiveEnv)
            `when`(aesUtil.decrypt("bad-payload")).thenThrow(RuntimeException("decrypt error"))

            // When
            val result = createService().getDecryptedValue(2L, 1L)

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("getDecryptedValue - 停用行不下发值")
        fun `getDecryptedValue should return null for a disabled variable`() {
            // Given - 这是下发解析值的那一个方法；开关不作用到这里就只是个摆设
            val paused = EnvVariable().apply {
                id = 2L
                envKey = "SECRET"
                envValue = "plain-payload"
                sensitive = 0
                enabled = 0
            }

            `when`(envVariableMapper.selectById(2L)).thenReturn(paused)

            // When & Then
            assertNull(createService().getDecryptedValue(2L, 1L))
        }
    }
}
