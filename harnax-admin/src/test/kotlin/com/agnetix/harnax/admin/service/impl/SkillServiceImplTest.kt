package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SkillCreateRequest
import com.agnetix.harnax.admin.dto.SkillUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.skill.loader.SkillLoader
import com.agnetix.harnax.admin.skill.loader.SkillLoaderRegistry
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.CliSkillBindingMapper
import com.agnetix.harnax.mapper.SkillMapper
import io.agentscope.core.skill.AgentSkill
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
 * SkillServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper and Service layer dependencies
 * Covers normal flows, exception flows, and boundary conditions
 *
 * @author agnetix
 * @since 2026-05-17
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillServiceImplTest {

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var skillMapper: SkillMapper

    @Mock
    private lateinit var skillRepositoryService: SkillRepositoryService

    @Mock
    private lateinit var agentSkillBindingMapper: AgentSkillBindingMapper

    @Mock
    private lateinit var cliSkillBindingMapper: CliSkillBindingMapper

    @Mock
    private lateinit var skillLoaderRegistry: SkillLoaderRegistry

    @Mock
    private lateinit var skillLoader: SkillLoader

    private lateinit var testSkill: Skill
    private lateinit var normalRepo: SkillRepository
    private lateinit var builtinRepo: SkillRepository

    @BeforeEach
    fun setUp() {
        normalRepo = SkillRepository().apply {
            id = 5L
            tenantId = 1L
            name = "qoder-skills"
            url = "https://github.com/test/skills"
            branch = "main"
            sourceType = "GIT"
            version = "v1.0.0"
            status = 1
            creator = "admin"
            active = 1
        }

        builtinRepo = SkillRepository().apply {
            id = 10L
            tenantId = 1L
            name = BuiltinRepository.CLI_SKILLS
            status = 1
            active = 1
        }

        testSkill = Skill().apply {
            id = 1L
            tenantId = 1L
            name = "code-review"
            repositoryId = 5L
            description = "Code review skill"
            skillmd = "# Code Review"
            resources = "{}"
            status = 1
            isPublic = 0
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

    private fun createService(): SkillServiceImpl = SkillServiceImpl(
        jwtUtil = jwtUtil,
        skillMapper = skillMapper,
        skillRepositoryService = skillRepositoryService,
        agentSkillBindingMapper = agentSkillBindingMapper,
        cliSkillBindingMapper = cliSkillBindingMapper,
        skillLoaderRegistry = skillLoaderRegistry,
        localTmpDir = "/tmp/harnax-skill-test",
    )

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Normal pagination query")
        fun `page should return paginated results`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(skillMapper.selectSkillList(null, null, null, "admin", 1L)).thenReturn(listOf(testSkill))

            // When
            val page = createService().page(null, null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(skillMapper).selectSkillList(null, null, null, "admin", 1L)
        }

        @Test
        @DisplayName("page - Filter by name and repositoryId and status")
        fun `page should filter by name repositoryId and status`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(skillMapper.selectSkillList("code", 5L, 1, "admin", 1L)).thenReturn(listOf(testSkill))

            // When
            val page = createService().page("code", 5L, 1, 1, 10)

            // Then
            assertNotNull(page)
            verify(skillMapper).selectSkillList("code", 5L, 1, "admin", 1L)
        }

        @Test
        @DisplayName("page - Use default tenantId 1 when TenantContext not set")
        fun `page should use default tenantId when TenantContext not set`() {
            // Given - TenantContext not set
            `when`(skillMapper.selectSkillList(null, null, null, "admin", 1L)).thenReturn(emptyList())

            // When
            val page = createService().page(null, null, null, 1, 10)

            // Then
            assertNotNull(page)
            verify(skillMapper).selectSkillList(null, null, null, "admin", 1L)
        }
    }

    @Nested
    @DisplayName("Get Skill Tests")
    inner class GetSkillTests {

        @Test
        @DisplayName("getSkill - Query by ID successfully")
        fun `getSkill should return skill by id`() {
            // Given
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)

            // When
            val result = createService().getSkill(1L)

            // Then
            assertNotNull(result)
            assertEquals("code-review", result?.name)
            verify(skillMapper).selectById(1L)
        }

        @Test
        @DisplayName("getSkill - Return null when not exists")
        fun `getSkill should return null when not exists`() {
            // Given
            `when`(skillMapper.selectById(999L)).thenReturn(null)

            // When
            val result = createService().getSkill(999L)

            // Then
            assertNull(result)
            verify(skillMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Create Skill Tests")
    inner class CreateSkillTests {

        @Test
        @DisplayName("createSkill - Create skill successfully")
        fun `createSkill should create skill successfully`() {
            // Given
            val request = SkillCreateRequest(
                name = "new-skill",
                repositoryId = 5L,
                description = "New skill",
                skillmd = "# New Skill",
                resources = "{}",
                status = 1,
            )

            `when`(skillMapper.selectByNameAndRepo("new-skill", 5L)).thenReturn(null)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createSkill(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("new-skill", saved.name)
            assertEquals(5L, saved.repositoryId)
            assertEquals("New skill", saved.description)
            assertEquals("admin", saved.creator)
            assertEquals(1, saved.active)
            assertEquals(0, saved.isPublic)
        }

        @Test
        @DisplayName("createSkill - Use default values for optional fields")
        fun `createSkill should use default values for optional fields`() {
            // Given
            val request = SkillCreateRequest(
                name = "minimal-skill",
                repositoryId = 5L,
            )

            `when`(skillMapper.selectByNameAndRepo("minimal-skill", 5L)).thenReturn(null)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createSkill(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("", saved.description)
            assertEquals("", saved.skillmd)
            assertEquals("", saved.resources)
            assertEquals(1, saved.status)
        }

        @Test
        @DisplayName("createSkill - Throw BizException when name is blank")
        fun `createSkill should throw BizException when name is blank`() {
            // Given
            val request = SkillCreateRequest(
                name = "   ",
                repositoryId = 5L,
            )

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createSkill(request)
            }
            assertEquals("Skill name cannot be empty", exception.message)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSkill - Throw BizException when repositoryId is null")
        fun `createSkill should throw BizException when repositoryId is null`() {
            // Given
            val request = SkillCreateRequest(
                name = "no-repo-skill",
                repositoryId = null,
            )

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createSkill(request)
            }
            assertEquals("Repository ID cannot be empty", exception.message)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSkill - Throw BizException when name exists")
        fun `createSkill should throw BizException when name exists`() {
            // Given
            val request = SkillCreateRequest(
                name = "code-review",
                repositoryId = 5L,
            )

            `when`(skillMapper.selectByNameAndRepo("code-review", 5L)).thenReturn(testSkill)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createSkill(request)
            }
            assertEquals("Skill name already exists", exception.message)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSkill - Throw BizException when repository is builtin")
        fun `createSkill should throw BizException when repository is builtin`() {
            // Given
            val request = SkillCreateRequest(
                name = "builtin-skill",
                repositoryId = 10L,
            )

            `when`(skillMapper.selectByNameAndRepo("builtin-skill", 10L)).thenReturn(null)
            `when`(skillRepositoryService.getSkillRepository(10L)).thenReturn(builtinRepo)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createSkill(request)
            }
            assertTrue(exception.message?.contains("read-only") == true)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSkill - Throw BizException when repository belongs to another tenant")
        fun `createSkill should throw BizException when repository belongs to another tenant`() {
            // Given
            TenantContext.setTenantId(2L)
            val request = SkillCreateRequest(
                name = "cross-tenant-skill",
                repositoryId = 5L,
            )

            `when`(skillMapper.selectByNameAndRepo("cross-tenant-skill", 5L)).thenReturn(null)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo) // tenantId = 1L

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createSkill(request)
            }
            assertEquals("Skill repository belongs to another tenant", exception.message)
            verify(skillMapper, never()).insert(any())
        }
    }

    @Nested
    @DisplayName("Update Skill Tests")
    inner class UpdateSkillTests {

        @Test
        @DisplayName("updateSkill - Update partial fields successfully")
        fun `updateSkill should update partial fields successfully`() {
            // Given
            val request = SkillUpdateRequest(
                description = "Updated description",
                skillmd = "# Updated",
            )

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateSkill(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).updateById(captor.capture())
            val updated = captor.firstValue
            assertEquals("Updated description", updated.description)
            assertEquals("# Updated", updated.skillmd)
            assertEquals("code-review", updated.name) // Unchanged
        }

        @Test
        @DisplayName("updateSkill - Rename skill when new name not used")
        fun `updateSkill should rename skill when new name not used`() {
            // Given
            val request = SkillUpdateRequest(name = "code-review-v2")

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.selectByNameAndRepo("code-review-v2", 5L)).thenReturn(null)
            `when`(skillMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateSkill(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).updateById(captor.capture())
            assertEquals("code-review-v2", captor.firstValue.name)
        }

        @Test
        @DisplayName("updateSkill - Throw BizException when skill not found")
        fun `updateSkill should throw BizException when skill not found`() {
            // Given
            val request = SkillUpdateRequest(description = "Updated")

            `when`(skillMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSkill(999L, request)
            }
            assertEquals("Skill not found", exception.message)
            verify(skillMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSkill - Throw BizException when new name exists")
        fun `updateSkill should throw BizException when new name exists`() {
            // Given
            val existSkill = Skill().apply {
                id = 2L
                name = "existing-skill"
                repositoryId = 5L
            }
            val request = SkillUpdateRequest(name = "existing-skill")

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.selectByNameAndRepo("existing-skill", 5L)).thenReturn(existSkill)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSkill(1L, request)
            }
            assertEquals("Skill name already exists", exception.message)
            verify(skillMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSkill - Throw BizException when skill belongs to builtin repository")
        fun `updateSkill should throw BizException when skill belongs to builtin repository`() {
            // Given
            val builtinSkill = Skill().apply {
                id = 3L
                name = "cli-skill"
                repositoryId = 10L
            }
            val request = SkillUpdateRequest(description = "Try update builtin")

            `when`(skillMapper.selectById(3L)).thenReturn(builtinSkill)
            `when`(skillRepositoryService.getSkillRepository(10L)).thenReturn(builtinRepo)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSkill(3L, request)
            }
            assertTrue(exception.message?.contains("read-only") == true)
            verify(skillMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSkill - Throw BizException when moving skill into builtin repository")
        fun `updateSkill should throw BizException when moving skill into builtin repository`() {
            // Given
            val request = SkillUpdateRequest(repositoryId = 10L)

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillRepositoryService.getSkillRepository(10L)).thenReturn(builtinRepo)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSkill(1L, request)
            }
            assertTrue(exception.message?.contains("read-only") == true)
            verify(skillMapper, never()).updateById(any())
        }
    }

    @Nested
    @DisplayName("Toggle Skill Status Tests")
    inner class ToggleSkillStatusTests {

        @Test
        @DisplayName("toggleSkillStatus - Disable skill successfully")
        fun `toggleSkillStatus should disable skill successfully`() {
            // Given
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = createService().toggleSkillStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(skillMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleSkillStatus - Enable skill successfully")
        fun `toggleSkillStatus should enable skill successfully`() {
            // Given
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = createService().toggleSkillStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(skillMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleSkillStatus - Throw BizException when skill not found")
        fun `toggleSkillStatus should throw BizException when skill not found`() {
            // Given
            `when`(skillMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().toggleSkillStatus(999L, 0)
            }
            assertEquals("Skill not found", exception.message)
            verify(skillMapper, never()).updateStatus(anyLong(), anyInt())
        }

        @Test
        @DisplayName("toggleSkillStatus - Throw BizException when skill belongs to builtin repository")
        fun `toggleSkillStatus should throw BizException when skill belongs to builtin repository`() {
            // Given
            val builtinSkill = Skill().apply {
                id = 3L
                name = "cli-skill"
                repositoryId = 10L
            }

            `when`(skillMapper.selectById(3L)).thenReturn(builtinSkill)
            `when`(skillRepositoryService.getSkillRepository(10L)).thenReturn(builtinRepo)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().toggleSkillStatus(3L, 0)
            }
            assertTrue(exception.message?.contains("read-only") == true)
            verify(skillMapper, never()).updateStatus(anyLong(), anyInt())
        }
    }

    @Nested
    @DisplayName("Delete Skill Tests")
    inner class DeleteSkillTests {

        @Test
        @DisplayName("deleteSkill - Delete skill and bindings successfully")
        fun `deleteSkill should delete skill and bindings successfully`() {
            // Given
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = createService().deleteSkill(1L)

            // Then
            assertTrue(result)
            verify(agentSkillBindingMapper).deleteBySkillIds(listOf(1L))
            verify(cliSkillBindingMapper).deleteBySkillIds(listOf(1L))
            verify(skillMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteSkill - Throw BizException when skill not found")
        fun `deleteSkill should throw BizException when skill not found`() {
            // Given
            `when`(skillMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().deleteSkill(999L)
            }
            assertEquals("Skill not found", exception.message)
            verify(skillMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteSkill - Throw BizException when skill belongs to builtin repository")
        fun `deleteSkill should throw BizException when skill belongs to builtin repository`() {
            // Given
            val builtinSkill = Skill().apply {
                id = 3L
                name = "cli-skill"
                repositoryId = 10L
            }

            `when`(skillMapper.selectById(3L)).thenReturn(builtinSkill)
            `when`(skillRepositoryService.getSkillRepository(10L)).thenReturn(builtinRepo)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().deleteSkill(3L)
            }
            assertTrue(exception.message?.contains("read-only") == true)
            verify(skillMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteSkill - Return false when delete affects no rows")
        fun `deleteSkill should return false when delete affects no rows`() {
            // Given
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.deleteById(1L)).thenReturn(0)

            // When
            val result = createService().deleteSkill(1L)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("Get By Name And Repo Tests")
    inner class GetByNameAndRepoTests {

        @Test
        @DisplayName("getByNameAndRepo - Query skill by name and repository successfully")
        fun `getByNameAndRepo should return skill by name and repository`() {
            // Given
            `when`(skillMapper.selectByNameAndRepo("code-review", 5L)).thenReturn(testSkill)

            // When
            val result = createService().getByNameAndRepo(5L, "code-review")

            // Then
            assertNotNull(result)
            assertEquals("code-review", result?.name)
            verify(skillMapper).selectByNameAndRepo("code-review", 5L)
        }

        @Test
        @DisplayName("getByNameAndRepo - Return null when not exists")
        fun `getByNameAndRepo should return null when not exists`() {
            // Given
            `when`(skillMapper.selectByNameAndRepo("not-exist", 5L)).thenReturn(null)

            // When
            val result = createService().getByNameAndRepo(5L, "not-exist")

            // Then
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Batch Save Skills Tests")
    inner class BatchSaveSkillsTests {

        @Test
        @DisplayName("batchSaveSkills - Return 0 when skills list is empty")
        fun `batchSaveSkills should return 0 when skills list is empty`() {
            // Given
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)

            // When
            val result = createService().batchSaveSkills(5L, emptyList())

            // Then
            assertEquals(0, result)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("batchSaveSkills - Throw BizException when repository is builtin")
        fun `batchSaveSkills should throw BizException when repository is builtin`() {
            // Given
            `when`(skillRepositoryService.getSkillRepository(10L)).thenReturn(builtinRepo)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().batchSaveSkills(10L, listOf("some-skill"))
            }
            assertTrue(exception.message?.contains("read-only") == true)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("batchSaveSkills - Throw BizException when repository not found")
        fun `batchSaveSkills should throw BizException when repository not found`() {
            // Given
            `when`(skillRepositoryService.getSkillRepository(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().batchSaveSkills(999L, listOf("some-skill"))
            }
            assertEquals("Skill repository not found", exception.message)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("batchSaveSkills - Insert new skill loaded from source")
        fun `batchSaveSkills should insert new skill loaded from source`() {
            // Given
            val agentSkill = AgentSkill.builder()
                .name("new-skill")
                .description("Loaded from source")
                .skillContent("# New Skill")
                .build()

            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(skillLoader)
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(listOf(agentSkill))
            `when`(skillMapper.selectByNameAndRepo("new-skill", 5L)).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().batchSaveSkills(5L, listOf("new-skill"))

            // Then
            assertEquals(1, result)
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("new-skill", saved.name)
            assertEquals("Loaded from source", saved.description)
            assertEquals("# New Skill", saved.skillmd)
            assertEquals("v1.0.0", saved.version)
            assertEquals("admin", saved.creator)
        }

        @Test
        @DisplayName("batchSaveSkills - Overwrite existing skill")
        fun `batchSaveSkills should overwrite existing skill`() {
            // Given
            val agentSkill = AgentSkill.builder()
                .name("code-review")
                .description("Refreshed description")
                .skillContent("# Refreshed")
                .build()

            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(skillLoader)
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(listOf(agentSkill))
            `when`(skillMapper.selectByNameAndRepo("code-review", 5L)).thenReturn(testSkill)
            `when`(skillMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().batchSaveSkills(5L, listOf("code-review"))

            // Then
            assertEquals(1, result)
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).updateById(captor.capture())
            val updated = captor.firstValue
            assertEquals("Refreshed description", updated.description)
            assertEquals("# Refreshed", updated.skillmd)
            assertEquals("v1.0.0", updated.version)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("batchSaveSkills - Skip skills not found in source")
        fun `batchSaveSkills should skip skills not found in source`() {
            // Given
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(skillLoader)
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(emptyList())

            // When
            val result = createService().batchSaveSkills(5L, listOf("missing-skill"))

            // Then
            assertEquals(0, result)
            verify(skillMapper, never()).insert(any())
            verify(skillMapper, never()).updateById(any())
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert skill with repository info")
        fun `convertToResponse should convert skill with repository info`() {
            // Given
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)

            // When
            val result = createService().convertToResponse(testSkill)

            // Then
            assertNotNull(result)
            assertEquals(1L, result.id)
            assertEquals("code-review", result.name)
            assertEquals("qoder-skills", result.repositoryName)
            assertEquals("https://github.com/test/skills", result.repositoryUrl)
            assertEquals("main", result.repositoryBranch)
            verify(skillRepositoryService).getSkillRepository(5L)
        }

        @Test
        @DisplayName("convertToResponse - Convert skill when repository not found")
        fun `convertToResponse should convert skill when repository not found`() {
            // Given
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(null)

            // When
            val result = createService().convertToResponse(testSkill)

            // Then
            assertNotNull(result)
            assertEquals("code-review", result.name)
            assertNull(result.repositoryName)
        }
    }

    @Nested
    @DisplayName("Convert To Responses Tests")
    inner class ConvertToResponsesTests {

        @Test
        @DisplayName("convertToResponses - Convert multiple skills caching repository lookups")
        fun `convertToResponses should convert multiple skills caching repository lookups`() {
            // Given
            val skill2 = Skill().apply {
                id = 2L
                name = "doc-writer"
                repositoryId = 5L
            }

            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)

            // When
            val result = createService().convertToResponses(listOf(testSkill, skill2))

            // Then
            assertEquals(2, result.size)
            assertEquals("code-review", result[0].name)
            assertEquals("doc-writer", result[1].name)
            assertEquals("qoder-skills", result[0].repositoryName)
            assertEquals("qoder-skills", result[1].repositoryName)
            // Distinct repository queried only once
            verify(skillRepositoryService).getSkillRepository(5L)
        }

        @Test
        @DisplayName("convertToResponses - Return empty list for empty input")
        fun `convertToResponses should return empty list for empty input`() {
            // When
            val result = createService().convertToResponses(emptyList())

            // Then
            assertTrue(result.isEmpty())
        }
    }
}
