package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SkillCreateRequest
import com.agnetix.harnax.admin.dto.SkillUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.skill.SkillInstaller
import com.agnetix.harnax.admin.skill.loader.SkillLoadFailure
import com.agnetix.harnax.admin.skill.loader.SkillLoadResult
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
            // Set on purpose: skills inherit this value and the entity default is public
            isPublic = 0
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
        // A real installer over the same mocked mappers, so the persistence assertions below still
        // describe what actually gets written
        skillInstaller = SkillInstaller(
            skillMapper = skillMapper,
            skillRepositoryMapper = org.mockito.kotlin.mock(),
            agentSkillBindingMapper = agentSkillBindingMapper,
            cliSkillBindingMapper = cliSkillBindingMapper,
        ),
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

        @Test
        @DisplayName("getSkill - Throw BizException when skill belongs to another tenant")
        fun `getSkill should throw BizException when skill belongs to another tenant`() {
            // Given
            TenantContext.setTenantId(2L)
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill) // tenantId = 1L
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().getSkill(1L)
            }
            assertEquals("Skill belongs to another tenant", exception.message)
        }

        @Test
        @DisplayName("getSkill - Allow builtin repository skills across tenants")
        fun `getSkill should allow builtin repository skills across tenants`() {
            // Given
            val builtinSkill = Skill().apply {
                id = 7L
                tenantId = 1L
                name = "cli-skill"
                repositoryId = 10L
            }
            TenantContext.setTenantId(2L)
            `when`(skillMapper.selectById(7L)).thenReturn(builtinSkill)
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(builtinRepo)

            // When
            val result = createService().getSkill(7L)

            // Then
            assertEquals("cli-skill", result?.name)
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
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createSkill(request)
            }
            assertEquals("Skill name already exists", exception.message)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSkill - Throw BizException when repository is missing")
        fun `createSkill should throw BizException when repository is missing`() {
            // Given
            val request = SkillCreateRequest(
                name = "orphan-skill",
                repositoryId = 999L,
            )

            `when`(skillRepositoryService.getSkillRepository(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().createSkill(request)
            }
            assertEquals("Skill repository not found", exception.message)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSkill - Inherit visibility from the repository")
        fun `createSkill should inherit visibility from the repository`() {
            // Given
            val publicRepo = SkillRepository().apply {
                id = 6L
                tenantId = 1L
                name = "public-skills"
                sourceType = "GIT"
                isPublic = 1
                creator = "admin"
                active = 1
            }
            val request = SkillCreateRequest(
                name = "public-skill",
                repositoryId = 6L,
            )

            `when`(skillRepositoryService.getSkillRepository(6L)).thenReturn(publicRepo)
            `when`(skillMapper.selectByNameAndRepo("public-skill", 6L)).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            // When
            assertTrue(createService().createSkill(request))

            // Then
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).insert(captor.capture())
            // A public repository whose skills stay private hides them from the list
            assertEquals(1, captor.firstValue.isPublic)
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

        @Test
        @DisplayName("createSkill - 带非法 status 时在查库之前就拒绝")
        fun `createSkill should reject an out-of-range status before touching the database`() {
            val request = SkillCreateRequest(
                name = "bad-status-skill",
                repositoryId = 5L,
                status = 7,
            )

            val exception = assertThrows<BizException> {
                createService().createSkill(request)
            }

            assertTrue(exception.message!!.contains("Status must be 0"))
            // 入参校验要先于任何库访问，否则一个非法请求也能探到仓库与名字是否存在
            verify(skillRepositoryService, never()).getSkillRepository(anyLong())
            verify(skillMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSkill - 显式传 status = 0 时存成停用")
        fun `createSkill should store an explicitly disabled skill`() {
            val request = SkillCreateRequest(
                name = "disabled-on-arrival",
                repositoryId = 5L,
                status = 0,
            )

            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.selectByNameAndRepo("disabled-on-arrival", 5L)).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            assertTrue(createService().createSkill(request))

            val captor = argumentCaptor<Skill>()
            verify(skillMapper).insert(captor.capture())
            assertEquals(0, captor.firstValue.status)
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

        @Test
        @DisplayName("updateSkill - Throw BizException when the move collides in the destination")
        fun `updateSkill should reject a move that collides with a name in the destination`() {
            // Given: 只换仓库、不改名字，而目标仓库里已经有同名技能
            val sharedRepo = SkillRepository().apply {
                id = 6L
                tenantId = 1L
                name = "shared-skills"
                sourceType = "GIT"
                isPublic = 1
            }
            val clash = Skill().apply {
                id = 9L
                name = "code-review"
                repositoryId = 6L
            }
            val request = SkillUpdateRequest(repositoryId = 6L)

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillRepositoryService.getSkillRepository(6L)).thenReturn(sharedRepo)
            `when`(skillMapper.selectByNameAndRepo("code-review", 6L)).thenReturn(clash)

            // When & Then
            // 之前不改名字就完全不查重，最后由唯一索引拿原始 SQL 报错回结
            val exception = assertThrows<BizException> {
                createService().updateSkill(1L, request)
            }
            assertEquals("Skill name already exists", exception.message)
            verify(skillMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSkill - Inherit the visibility of the destination repository")
        fun `updateSkill should inherit the visibility of the destination repository`() {
            // Given
            val sharedRepo = SkillRepository().apply {
                id = 6L
                tenantId = 1L
                name = "shared-skills"
                sourceType = "GIT"
                isPublic = 1
            }
            val request = SkillUpdateRequest(repositoryId = 6L)

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillRepositoryService.getSkillRepository(6L)).thenReturn(sharedRepo)
            `when`(skillMapper.selectByNameAndRepo("code-review", 6L)).thenReturn(null)
            `when`(skillMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateSkill(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).updateById(captor.capture())
            // 创建和同步都按仓库继承可见性；换仓库时不继承，私有技能移进公开仓库后仍然看不见
            assertEquals(6L, captor.firstValue.repositoryId)
            assertEquals(1, captor.firstValue.isPublic)
        }

        @Test
        @DisplayName("updateSkill - Trim the incoming name")
        fun `updateSkill should trim the incoming name`() {
            // Given
            val request = SkillUpdateRequest(name = "  code-review-v2  ")

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.selectByNameAndRepo("code-review-v2", 5L)).thenReturn(null)
            `when`(skillMapper.updateById(any())).thenReturn(1)

            // When
            createService().updateSkill(1L, request)

            // Then
            // 名字要参与唯一索引比较，存进带空格的值会让同一个技能被存两次
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).updateById(captor.capture())
            assertEquals("code-review-v2", captor.firstValue.name)
        }

        @Test
        @DisplayName("updateSkill - Throw BizException when the incoming name is blank")
        fun `updateSkill should reject a blank name`() {
            // Given
            val request = SkillUpdateRequest(name = "   ")

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSkill(1L, request)
            }
            assertEquals("Skill name cannot be empty", exception.message)
            verify(skillMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSkill - 带 status 时走专用的 updateStatus")
        fun `updateSkill should route a status change to the dedicated update statement`() {
            // updateById 的 SQL 里没有 status 列（启停只能走 toggle），而 DTO 一直声明了这个字段。
            // 之前不读它，接口回 200 而库里没变，`harnax skill update <id> --status 0` 就属于这种
            val request = SkillUpdateRequest(status = 0)

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.updateStatus(1L, 0)).thenReturn(1)
            `when`(skillMapper.updateById(any())).thenReturn(1)

            assertTrue(createService().updateSkill(1L, request))

            verify(skillMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("updateSkill - 请求不带 status 时不去动它")
        fun `updateSkill should leave status alone when the request omits it`() {
            // 重新导入时 SkillContentScanner 可能把技能降为待审核，一次改描述的编辑不应把它又打开
            val request = SkillUpdateRequest(description = "Updated description")

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillMapper.updateById(any())).thenReturn(1)

            assertTrue(createService().updateSkill(1L, request))

            verify(skillMapper, never()).updateStatus(anyLong(), anyInt())
        }

        @Test
        @DisplayName("updateSkill - 带非法 status 时拒绝")
        fun `updateSkill should reject an out-of-range status`() {
            // status 存进 TINYINT 不报错，但全链路都用 status == 1 判断，写个 7 进去等于造出一个
            // 既不能在页面切换、也永远不会被加载的技能
            val request = SkillUpdateRequest(status = 7)

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)

            val exception = assertThrows<BizException> {
                createService().updateSkill(1L, request)
            }

            assertTrue(exception.message!!.contains("Status must be 0"))
            verify(skillMapper, never()).updateStatus(anyLong(), anyInt())
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

        @Test
        @DisplayName("toggleSkillStatus - Throw BizException when status is not 0 or 1")
        fun `toggleSkillStatus should reject a status outside 0 and 1`() {
            // When & Then
            // status 是两态开关，消费方一律按 == 1 判断，写进 99 会让这一行永远显示为禁用
            val exception = assertThrows<BizException> {
                createService().toggleSkillStatus(1L, 99)
            }

            assertTrue(exception.message!!.contains("0 (disabled) or 1 (enabled)"))
            verify(skillMapper, never()).selectById(anyLong())
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
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(agentSkill)))
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
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(agentSkill)))
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
        @DisplayName("batchSaveSkills - Report skills not found in source")
        fun `batchSaveSkills should report skills not found in source`() {
            // Given
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(skillLoader)
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(SkillLoadResult(emptyList()))

            // When
            val result = createService().batchSaveSkillsDetailed(5L, listOf("missing-skill"))

            // Then
            assertEquals(0, result.savedCount)
            // Dropping a selection used to look like success; it is now reported back
            assertFalse(result.complete)
            assertEquals(listOf("missing-skill"), result.failed.map { it.name })
            verify(skillMapper, never()).insert(any())
            verify(skillMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("batchSaveSkills - Report a directory the loader could not parse")
        fun `batchSaveSkills should report a directory the loader could not parse`() {
            // Given：源里两个目录，一个能读、一个 SKILL.md 解析失败
            val readable = AgentSkill.builder()
                .name("readable")
                .skillContent("# Readable")
                .description("Readable skill")
                .build()
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(skillLoader)
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(
                SkillLoadResult(
                    listOf(readable),
                    listOf(SkillLoadFailure("broken-dir", "SKILL.md could not be parsed: MalformedInputException")),
                ),
            )
            `when`(skillMapper.selectByNameAndRepo("readable", 5L)).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            // When：两个目录都在勾选清单里
            val result = createService().batchSaveSkillsDetailed(5L, listOf("readable", "broken-dir"))

            // Then：读不出来的那个必须带原因回来，否则「勾了 2 个只落库 1 个」看起来像成功
            assertEquals(listOf("readable"), result.installed)
            assertEquals(listOf("broken-dir"), result.failed.map { it.name })
            assertTrue(result.failed[0].reason.contains("could not be parsed"))
            assertFalse(result.complete)
        }

        @Test
        @DisplayName("batchSaveSkills - Ignore load failures outside the selection")
        fun `batchSaveSkills should not report load failures it was not asked about`() {
            // Given：勾选清单里只有一个技能，源里另有一个目录解析失败
            val wanted = AgentSkill.builder()
                .name("wanted")
                .skillContent("# Wanted")
                .description("Wanted skill")
                .build()
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(skillLoader)
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(
                SkillLoadResult(
                    listOf(wanted),
                    listOf(SkillLoadFailure("other-broken", "SKILL.md is empty")),
                ),
            )
            `when`(skillMapper.selectByNameAndRepo("wanted", 5L)).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().batchSaveSkillsDetailed(5L, listOf("wanted"))

            // Then：没勾的目录不该出现在报告里，否则操作者会以为自己选错了
            assertEquals(listOf("wanted"), result.installed)
            assertTrue(result.failed.isEmpty())
            assertTrue(result.complete)
        }

        @Test
        @DisplayName("batchSaveSkills - Store flagged skills disabled for review")
        fun `batchSaveSkills should store flagged skills disabled for review`() {
            // Given
            val dangerous = AgentSkill.builder()
                .name("wiper")
                .description("Wipes the disk")
                .skillContent("# Wiper\n\nRun `rm -rf /` to clean up.")
                .build()

            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(skillLoader)
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(dangerous)))
            `when`(skillMapper.selectByNameAndRepo("wiper", 5L)).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().batchSaveSkillsDetailed(5L, listOf("wiper"))

            // Then
            assertEquals(1, result.savedCount)
            assertEquals(listOf("wiper"), result.flagged.map { it.name })
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).insert(captor.capture())
            assertEquals(0, captor.firstValue.status)
        }

        @Test
        @DisplayName("batchSaveSkills - Throw BizException when the repository is a ZIP source")
        fun `batchSaveSkills should refuse a ZIP source`() {
            // Given
            val zipRepo = SkillRepository().apply {
                id = 7L
                tenantId = 1L
                name = "uploaded-zip"
                sourceType = "ZIP"
                isPublic = 0
            }
            `when`(skillRepositoryService.getSkillRepository(7L)).thenReturn(zipRepo)

            // When & Then
            // ZIP 上传后不留档，之前这条路径只会回一句 ZIP loader 的 requires 'zipPath'
            val exception = assertThrows<BizException> {
                createService().batchSaveSkills(7L, listOf("any-skill"))
            }
            assertTrue(exception.message!!.contains("installed once"))
            verify(skillLoaderRegistry, never()).getLoader(anyString())
        }

        @Test
        @DisplayName("batchSaveSkills - Throw BizException when the selection exceeds the ceiling")
        fun `batchSaveSkills should refuse an oversized selection`() {
            // Given
            val oversized = (1..1001).map { "skill-$it" }
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)

            // When & Then
            // 源里没有的名字会逐个回到失败清单里，不设上限就能用一个请求撑爆响应体
            val exception = assertThrows<BizException> {
                createService().batchSaveSkills(5L, oversized)
            }
            assertTrue(exception.message!!.contains("at most 1000"))
            verify(skillLoaderRegistry, never()).getLoader(anyString())
        }

        @Test
        @DisplayName("batchSaveSkills - Resolve a source skill whose declared name carries padding")
        fun `batchSaveSkills should resolve a source name that carries padding`() {
            // Given
            // 源里声明的名字带空格，而落库存的是 trim 后的值。两边不一致时，明明在源里的技能
            // 会被报成「源里已经没有它了」
            val padded = AgentSkill.builder()
                .name("  padded-skill  ")
                .description("Declared with padding")
                .skillContent("# Padded")
                .build()

            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(skillLoader)
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(padded)))
            `when`(skillMapper.selectByNameAndRepo("padded-skill", 5L)).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            // When：调用方传的是 trim 之后的名字，也就是预览接口现在返回的那一个
            val result = createService().batchSaveSkillsDetailed(5L, listOf("padded-skill"))

            // Then
            assertEquals(1, result.savedCount)
            assertTrue(result.complete)
            val captor = argumentCaptor<Skill>()
            verify(skillMapper).insert(captor.capture())
            assertEquals("padded-skill", captor.firstValue.name)
        }

        @Test
        @DisplayName("batchSaveSkills - Accept a padded selection from a caller that did not trim")
        fun `batchSaveSkills should trim the selection itself`() {
            // Given
            val skill = AgentSkill.builder()
                .name("cli-skill")
                .description("Picked by the CLI")
                .skillContent("# CLI Skill")
                .build()

            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(skillLoader)
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(skill)))
            `when`(skillMapper.selectByNameAndRepo("cli-skill", 5L)).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            // When：CLI 和小程序共用这个端点，不能假设它们都做过 trim
            val result = createService().batchSaveSkillsDetailed(5L, listOf("  cli-skill  ", "", "cli-skill"))

            // Then：三个选择项归一化后是同一个技能，只写一行
            assertEquals(1, result.savedCount)
            assertTrue(result.complete)
            verify(skillMapper).insert(any())
        }

        @Test
        @DisplayName("batchSaveSkills - Report an over-long name instead of a SQL truncation error")
        fun `batchSaveSkills should report an over-long skill name`() {
            // Given
            // `skill.name` 是 varchar(100)，放过去只会让 MySQL 回一句「Data too long for column」
            val longName = "s".repeat(150)
            val oversized = AgentSkill.builder()
                .name(longName)
                .description("A skill whose name does not fit the column")
                .skillContent("# Oversized")
                .build()

            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(normalRepo)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(skillLoader)
            `when`(skillLoader.loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(oversized)))

            // When
            val result = createService().batchSaveSkillsDetailed(5L, listOf(longName))

            // Then
            assertEquals(0, result.savedCount)
            assertFalse(result.complete)
            assertTrue(result.failed[0].reason.contains("longer than the 100 characters"))
            // 回报里也要短，否则一条失败明细就把通知撑满
            assertEquals("s".repeat(100) + "...", result.failed[0].name)
            verify(skillMapper, never()).insert(any())
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
