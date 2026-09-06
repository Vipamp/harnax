package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.CliCreateRequest
import com.agnetix.harnax.admin.dto.CliUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.Cli
import com.agnetix.harnax.entity.CliSkillBinding
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.CliSkillBindingMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
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
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Spy
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

/**
 * CliServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper and Service layer dependencies
 * Covers normal flows, exception flows, and boundary conditions
 *
 * @author agnetix
 * @since 2026-05-17
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CliServiceImplTest {

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var cliMapper: CliMapper

    @Mock
    private lateinit var cliSkillBindingMapper: CliSkillBindingMapper

    @Mock
    private lateinit var skillMapper: SkillMapper

    @Mock
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    @Mock
    private lateinit var agentSessionRefreshService: AgentSessionRefreshService

    @Spy
    private var objectMapper: ObjectMapper = ObjectMapper()

    @Mock
    private lateinit var secretFieldEncryptor: SecretFieldEncryptor

    private lateinit var testCli: Cli
    private lateinit var testSkill: Skill
    private lateinit var builtinRepo: SkillRepository

    @BeforeEach
    fun setUp() {
        testCli = Cli().apply {
            id = 1L
            tenantId = 1L
            name = "kubectl"
            description = "Kubernetes CLI"
            version = "1.30.0"
            installScript = "RUN curl -LO kubectl && install kubectl"
            checkCommand = "kubectl version --client"
            envParams = """[{"key":"KUBECONFIG"}]"""
            status = 1
            isPublic = 0
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        builtinRepo = SkillRepository().apply {
            id = 10L
            tenantId = 1L
            name = BuiltinRepository.CLI_SKILLS
            status = 1
            active = 1
        }

        testSkill = Skill().apply {
            id = 100L
            tenantId = 1L
            name = "kubectl-usage"
            repositoryId = 10L
            description = "How to use kubectl"
            status = 1
            active = 1
        }

        // Mock HttpServletRequest for UserContextUtil
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))

        // Mock JwtUtil
        `when`(jwtUtil.validateToken(any())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        RequestContextHolder.resetRequestAttributes()
    }

    private fun createService(): CliServiceImpl = CliServiceImpl(
        jwtUtil = jwtUtil,
        cliMapper = cliMapper,
        cliSkillBindingMapper = cliSkillBindingMapper,
        skillMapper = skillMapper,
        skillRepositoryMapper = skillRepositoryMapper,
        agentSessionRefreshService = agentSessionRefreshService,
        secretFieldEncryptor = secretFieldEncryptor,
        objectMapper = objectMapper,
    )

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Normal pagination query")
        fun `page should return paginated results`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(cliMapper.selectCliList(null, null, "admin", 1L)).thenReturn(listOf(testCli))

            // When
            val page = createService().page(null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(cliMapper).selectCliList(null, null, "admin", 1L)
        }

        @Test
        @DisplayName("page - Filter by name")
        fun `page should filter by name`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(cliMapper.selectCliList("kube", null, "admin", 1L)).thenReturn(listOf(testCli))

            // When
            val page = createService().page("kube", null, 1, 10)

            // Then
            assertNotNull(page)
            verify(cliMapper).selectCliList("kube", null, "admin", 1L)
        }

        @Test
        @DisplayName("page - Filter by status")
        fun `page should filter by status`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(cliMapper.selectCliList(null, 1, "admin", 1L)).thenReturn(listOf(testCli))

            // When
            val page = createService().page(null, 1, 1, 10)

            // Then
            assertNotNull(page)
            verify(cliMapper).selectCliList(null, 1, "admin", 1L)
        }

        @Test
        @DisplayName("page - Use default tenantId 1 when TenantContext not set")
        fun `page should use default tenantId when TenantContext not set`() {
            // Given - TenantContext not set
            `when`(cliMapper.selectCliList(null, null, "admin", 1L)).thenReturn(emptyList())

            // When
            val page = createService().page(null, null, 1, 10)

            // Then
            assertNotNull(page)
            verify(cliMapper).selectCliList(null, null, "admin", 1L)
        }
    }

    @Nested
    @DisplayName("Get CLI Tests")
    inner class GetCliTests {

        @Test
        @DisplayName("getCli - Query by ID successfully")
        fun `getCli should return cli by id`() {
            // Given
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)

            // When
            val result = createService().getCli(1L)

            // Then
            assertNotNull(result)
            assertEquals("kubectl", result?.name)
            assertEquals("1.30.0", result?.version)
            verify(cliMapper).selectById(1L)
        }

        @Test
        @DisplayName("getCli - Return null when not exists")
        fun `getCli should return null when not exists`() {
            // Given
            `when`(cliMapper.selectById(999L)).thenReturn(null)

            // When
            val result = createService().getCli(999L)

            // Then
            assertNull(result)
            verify(cliMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Create CLI Tests")
    inner class CreateCliTests {

        @Test
        @DisplayName("createCli - Create CLI without skills successfully")
        fun `createCli should create cli without skills successfully`() {
            // Given
            val request = CliCreateRequest(
                name = "gh",
                description = "GitHub CLI",
                version = "2.50.0",
                installScript = "RUN apt-get install gh",
                checkCommand = "gh --version",
                status = 1,
                isPublic = 0,
            )

            `when`(cliMapper.selectByName("gh", 1L)).thenReturn(null)
            `when`(cliMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createCli(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Cli>()
            verify(cliMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("gh", saved.name)
            assertEquals("GitHub CLI", saved.description)
            assertEquals("2.50.0", saved.version)
            assertEquals("admin", saved.creator)
            assertEquals(1, saved.active)
            // skillIds is null, bindings are still cleared
            verify(cliSkillBindingMapper).deleteByCliId(saved.id)
            verify(cliSkillBindingMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("createCli - Create CLI with default values for optional fields")
        fun `createCli should use default values for optional fields`() {
            // Given
            val request = CliCreateRequest(
                name = "awscli",
                installScript = "RUN pip install awscli",
            )

            `when`(cliMapper.selectByName("awscli", 1L)).thenReturn(null)
            `when`(cliMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createCli(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Cli>()
            verify(cliMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("", saved.description)
            assertEquals("", saved.version)
            assertEquals("", saved.checkCommand)
            assertEquals(1, saved.status)
            assertEquals(0, saved.isPublic)
        }

        @Test
        @DisplayName("createCli - Create CLI with skill bindings successfully")
        fun `createCli should create cli with skill bindings successfully`() {
            // Given
            val request = CliCreateRequest(
                name = "gh",
                installScript = "RUN apt-get install gh",
                skillIds = listOf(100L),
            )

            `when`(cliMapper.selectByName("gh", 1L)).thenReturn(null)
            `when`(cliMapper.insert(any())).thenReturn(1)
            `when`(skillMapper.selectByIds(listOf(100L))).thenReturn(listOf(testSkill))
            `when`(skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)).thenReturn(builtinRepo)
            `when`(cliSkillBindingMapper.batchInsert(any())).thenReturn(1)

            // When
            val result = createService().createCli(request)

            // Then
            assertTrue(result)
            val bindingCaptor = argumentCaptor<List<CliSkillBinding>>()
            verify(cliSkillBindingMapper).batchInsert(bindingCaptor.capture())
            assertEquals(1, bindingCaptor.firstValue.size)
            assertEquals(100L, bindingCaptor.firstValue[0].skillId)
        }

        @Test
        @DisplayName("createCli - Throw BizException when name exists")
        fun `createCli should throw BizException when name exists`() {
            // Given
            val request = CliCreateRequest(
                name = "kubectl",
                installScript = "RUN install kubectl",
            )

            `when`(cliMapper.selectByName("kubectl", 1L)).thenReturn(testCli)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createCli(request)
            }
            assertEquals("CLI name already exists", exception.message)
            verify(cliMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createCli - Throw BizException when some skills not found")
        fun `createCli should throw BizException when some skills not found`() {
            // Given
            val request = CliCreateRequest(
                name = "gh",
                installScript = "RUN apt-get install gh",
                skillIds = listOf(100L, 999L),
            )

            `when`(cliMapper.selectByName("gh", 1L)).thenReturn(null)
            `when`(cliMapper.insert(any())).thenReturn(1)
            `when`(skillMapper.selectByIds(listOf(100L, 999L))).thenReturn(listOf(testSkill))

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createCli(request)
            }
            assertTrue(exception.message?.contains("Some skills not found") == true)
            verify(cliSkillBindingMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("createCli - Throw BizException when builtin repository missing")
        fun `createCli should throw BizException when builtin repository missing`() {
            // Given
            val request = CliCreateRequest(
                name = "gh",
                installScript = "RUN apt-get install gh",
                skillIds = listOf(100L),
            )

            `when`(cliMapper.selectByName("gh", 1L)).thenReturn(null)
            `when`(cliMapper.insert(any())).thenReturn(1)
            `when`(skillMapper.selectByIds(listOf(100L))).thenReturn(listOf(testSkill))
            `when`(skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createCli(request)
            }
            assertTrue(exception.message?.contains("not found") == true)
            verify(cliSkillBindingMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("createCli - Throw BizException when skill not from builtin repository")
        fun `createCli should throw BizException when skill not from builtin repository`() {
            // Given
            val outsideSkill = Skill().apply {
                id = 200L
                name = "other-skill"
                repositoryId = 20L // Not builtin repo (10L)
            }

            val request = CliCreateRequest(
                name = "gh",
                installScript = "RUN apt-get install gh",
                skillIds = listOf(200L),
            )

            `when`(cliMapper.selectByName("gh", 1L)).thenReturn(null)
            `when`(cliMapper.insert(any())).thenReturn(1)
            `when`(skillMapper.selectByIds(listOf(200L))).thenReturn(listOf(outsideSkill))
            `when`(skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)).thenReturn(builtinRepo)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createCli(request)
            }
            assertTrue(exception.message?.contains("must belong to") == true)
            verify(cliSkillBindingMapper, never()).batchInsert(any())
        }
    }

    @Nested
    @DisplayName("Update CLI Tests")
    inner class UpdateCliTests {

        @Test
        @DisplayName("updateCli - Update partial fields successfully")
        fun `updateCli should update partial fields successfully`() {
            // Given
            val request = CliUpdateRequest(
                description = "Updated description",
                version = "1.31.0",
            )

            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateCli(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Cli>()
            verify(cliMapper).updateById(captor.capture())
            val updated = captor.firstValue
            assertEquals("Updated description", updated.description)
            assertEquals("1.31.0", updated.version)
            assertEquals("kubectl", updated.name) // Unchanged
            // skillIds not provided, bindings untouched
            verify(cliSkillBindingMapper, never()).deleteByCliId(anyLong())
        }

        @Test
        @DisplayName("updateCli - Rename CLI when new name not used")
        fun `updateCli should rename cli when new name not used`() {
            // Given
            val request = CliUpdateRequest(name = "kubectl-v2")

            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.selectByName("kubectl-v2", 1L)).thenReturn(null)
            `when`(cliMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateCli(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Cli>()
            verify(cliMapper).updateById(captor.capture())
            assertEquals("kubectl-v2", captor.firstValue.name)
        }

        @Test
        @DisplayName("updateCli - Throw BizException when CLI not found")
        fun `updateCli should throw BizException when cli not found`() {
            // Given
            val request = CliUpdateRequest(description = "Updated")

            `when`(cliMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateCli(999L, request)
            }
            assertEquals("CLI not found", exception.message)
            verify(cliMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateCli - Throw BizException when new name exists")
        fun `updateCli should throw BizException when new name exists`() {
            // Given
            val exist = Cli().apply {
                id = 2L
                name = "gh"
                tenantId = 1L
            }
            val request = CliUpdateRequest(name = "gh")

            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.selectByName("gh", 1L)).thenReturn(exist)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateCli(1L, request)
            }
            assertEquals("CLI name already exists", exception.message)
            verify(cliMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateCli - Throw BizException when CLI belongs to another tenant")
        fun `updateCli should throw BizException when cli belongs to another tenant`() {
            // Given
            TenantContext.setTenantId(2L)
            val request = CliUpdateRequest(description = "Cross tenant update")

            `when`(cliMapper.selectById(1L)).thenReturn(testCli) // tenantId = 1L

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateCli(1L, request)
            }
            assertEquals("CLI belongs to another tenant", exception.message)
            verify(cliMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateCli - Rebuild skill bindings when skillIds provided")
        fun `updateCli should rebuild skill bindings when skillIds provided`() {
            // Given
            val request = CliUpdateRequest(skillIds = listOf(100L))

            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateById(any())).thenReturn(1)
            `when`(skillMapper.selectByIds(listOf(100L))).thenReturn(listOf(testSkill))
            `when`(skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)).thenReturn(builtinRepo)
            `when`(cliSkillBindingMapper.batchInsert(any())).thenReturn(1)

            // When
            val result = createService().updateCli(1L, request)

            // Then
            assertTrue(result)
            verify(cliSkillBindingMapper).deleteByCliId(1L)
            verify(cliSkillBindingMapper).batchInsert(any())
        }

        @Test
        @DisplayName("updateCli - Clear skill bindings when skillIds is empty list")
        fun `updateCli should clear skill bindings when skillIds is empty list`() {
            // Given
            val request = CliUpdateRequest(skillIds = emptyList())

            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateCli(1L, request)

            // Then
            assertTrue(result)
            verify(cliSkillBindingMapper).deleteByCliId(1L)
            verify(cliSkillBindingMapper, never()).batchInsert(any())
        }
    }

    @Nested
    @DisplayName("Toggle Status Tests")
    inner class ToggleStatusTests {

        @Test
        @DisplayName("toggleCliStatus - Enable CLI successfully")
        fun `toggleCliStatus should enable cli successfully`() {
            // Given
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = createService().toggleCliStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(cliMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleCliStatus - Disable CLI when no enabled agents depend on it")
        fun `toggleCliStatus should disable cli when no enabled agents depend on it`() {
            // Given
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(agentSessionRefreshService.listAgentsByCli(1L)).thenReturn(emptyList())
            `when`(cliMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = createService().toggleCliStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(agentSessionRefreshService).listAgentsByCli(1L)
            verify(cliMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleCliStatus - Disable CLI when only disabled agents depend on it")
        fun `toggleCliStatus should disable cli when only disabled agents depend on it`() {
            // Given
            val disabledAgent = RelatedAgentInfo(agentId = 100L, agentName = "Disabled Agent", status = 0)
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(agentSessionRefreshService.listAgentsByCli(1L)).thenReturn(listOf(disabledAgent))
            `when`(cliMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = createService().toggleCliStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(cliMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleCliStatus - Throw BizException when enabled agents still depend on it")
        fun `toggleCliStatus should throw BizException when enabled agents depend on it`() {
            // Given
            val enabledAgent = RelatedAgentInfo(agentId = 100L, agentName = "Enabled Agent", status = 1)
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(agentSessionRefreshService.listAgentsByCli(1L)).thenReturn(listOf(enabledAgent))

            // When & Then
            val exception = assertThrows<BizException> {
                createService().toggleCliStatus(1L, 0)
            }
            assertTrue(exception.message?.contains("Enabled Agent") == true)
            verify(cliMapper, never()).updateStatus(anyLong(), anyInt())
        }

        @Test
        @DisplayName("toggleCliStatus - Throw BizException when CLI not found")
        fun `toggleCliStatus should throw BizException when cli not found`() {
            // Given
            `when`(cliMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().toggleCliStatus(999L, 0)
            }
            assertEquals("CLI not found", exception.message)
            verify(cliMapper, never()).updateStatus(anyLong(), anyInt())
        }

        @Test
        @DisplayName("toggleCliStatus - Throw BizException when CLI belongs to another tenant")
        fun `toggleCliStatus should throw BizException when cli belongs to another tenant`() {
            // Given
            TenantContext.setTenantId(99L)
            `when`(cliMapper.selectById(1L)).thenReturn(testCli) // tenantId = 1L

            // When & Then
            val exception = assertThrows<BizException> {
                createService().toggleCliStatus(1L, 1)
            }
            assertEquals("CLI belongs to another tenant", exception.message)
        }
    }

    @Nested
    @DisplayName("Delete CLI Tests")
    inner class DeleteCliTests {

        @Test
        @DisplayName("deleteCli - Delete CLI and its bindings successfully")
        fun `deleteCli should delete cli and bindings successfully`() {
            // Given
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(agentSessionRefreshService.listAgentsByCli(1L)).thenReturn(emptyList())
            `when`(cliMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = createService().deleteCli(1L)

            // Then
            assertTrue(result)
            verify(cliSkillBindingMapper).deleteByCliId(1L)
            verify(cliMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteCli - Throw BizException when CLI not found")
        fun `deleteCli should throw BizException when cli not found`() {
            // Given
            `when`(cliMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().deleteCli(999L)
            }
            assertEquals("CLI not found", exception.message)
            verify(cliMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteCli - Throw BizException when CLI still bound to agents")
        fun `deleteCli should throw BizException when cli still bound to agents`() {
            // Given
            val boundAgent = RelatedAgentInfo(agentId = 100L, agentName = "Bound Agent", status = 0)
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(agentSessionRefreshService.listAgentsByCli(1L)).thenReturn(listOf(boundAgent))

            // When & Then
            val exception = assertThrows<BizException> {
                createService().deleteCli(1L)
            }
            assertTrue(exception.message?.contains("Bound Agent") == true)
            verify(cliSkillBindingMapper, never()).deleteByCliId(anyLong())
            verify(cliMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteCli - Throw BizException when CLI belongs to another tenant")
        fun `deleteCli should throw BizException when cli belongs to another tenant`() {
            // Given
            TenantContext.setTenantId(2L)
            `when`(cliMapper.selectById(1L)).thenReturn(testCli) // tenantId = 1L

            // When & Then
            val exception = assertThrows<BizException> {
                createService().deleteCli(1L)
            }
            assertEquals("CLI belongs to another tenant", exception.message)
            verify(cliMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteCli - Return false when delete affects no rows")
        fun `deleteCli should return false when delete affects no rows`() {
            // Given
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(agentSessionRefreshService.listAgentsByCli(1L)).thenReturn(emptyList())
            `when`(cliMapper.deleteById(1L)).thenReturn(0)

            // When
            val result = createService().deleteCli(1L)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert CLI with skill bindings")
        fun `convertToResponse should convert cli with skill bindings`() {
            // Given
            val binding = CliSkillBinding().apply {
                cliId = 1L
                skillId = 100L
            }

            `when`(cliSkillBindingMapper.selectByCliId(1L)).thenReturn(listOf(binding))
            `when`(skillMapper.selectByIds(listOf(100L))).thenReturn(listOf(testSkill))

            // When
            val result = createService().convertToResponse(testCli)

            // Then
            assertNotNull(result)
            assertEquals(testCli.id, result.id)
            assertEquals("kubectl", result.name)
            assertEquals(1, result.skillList?.size)
            assertEquals(100L, result.skillList?.get(0)?.skillId)
            assertEquals("kubectl-usage", result.skillList?.get(0)?.skillName)
            verify(cliSkillBindingMapper).selectByCliId(1L)
            verify(skillMapper).selectByIds(listOf(100L))
        }

        @Test
        @DisplayName("convertToResponse - Convert CLI without skill bindings")
        fun `convertToResponse should convert cli without skill bindings`() {
            // Given
            `when`(cliSkillBindingMapper.selectByCliId(1L)).thenReturn(emptyList())

            // When
            val result = createService().convertToResponse(testCli)

            // Then
            assertNotNull(result)
            assertNull(result.skillList)
            verify(skillMapper, never()).selectByIds(any())
        }

        @Test
        @DisplayName("convertToResponse - Skip bindings whose skill no longer exists")
        fun `convertToResponse should skip bindings whose skill no longer exists`() {
            // Given
            val orphanBinding = CliSkillBinding().apply {
                cliId = 1L
                skillId = 999L
            }

            `when`(cliSkillBindingMapper.selectByCliId(1L)).thenReturn(listOf(orphanBinding))
            `when`(skillMapper.selectByIds(listOf(999L))).thenReturn(emptyList())

            // When
            val result = createService().convertToResponse(testCli)

            // Then
            assertNotNull(result)
            assertTrue(result.skillList?.isEmpty() == true)
        }
    }
}
