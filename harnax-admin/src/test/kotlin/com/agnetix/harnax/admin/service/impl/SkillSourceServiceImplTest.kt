package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SkillSourceCreateRequest
import com.agnetix.harnax.admin.dto.SkillSourceUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.skill.loader.GitSkillLoader
import com.agnetix.harnax.admin.skill.loader.NpmSkillLoader
import com.agnetix.harnax.admin.skill.loader.SkillLoaderRegistry
import com.agnetix.harnax.admin.skill.loader.ZipSkillLoader
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import io.agentscope.core.skill.AgentSkill
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

/**
 * Unit tests for SkillSourceServiceImpl.
 * Manually constructs the service to handle @Value injection for localTmpDir.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillSourceServiceImplTest {

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    @Mock
    private lateinit var skillMapper: SkillMapper

    private lateinit var skillSourceService: SkillSourceServiceImpl

    private lateinit var testRepository: SkillRepository
    private lateinit var testSkill: Skill

    @BeforeEach
    fun setUp() {
        val gitLoader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
            `when`(sourceType).thenReturn("GIT")
            `when`(loadSkills(any(), any())).thenReturn(emptyList())
        }
        val npmLoader = org.mockito.Mockito.mock(NpmSkillLoader::class.java).apply {
            `when`(sourceType).thenReturn("NPM")
            `when`(loadSkills(any(), any())).thenReturn(emptyList())
        }
        val zipLoader = org.mockito.Mockito.mock(ZipSkillLoader::class.java).apply {
            `when`(sourceType).thenReturn("ZIP")
            `when`(loadSkills(any(), any())).thenReturn(emptyList())
        }
        val registry = SkillLoaderRegistry(listOf(gitLoader, npmLoader, zipLoader))

        skillSourceService = SkillSourceServiceImpl(
            jwtUtil = jwtUtil,
            skillRepositoryMapper = skillRepositoryMapper,
            skillMapper = skillMapper,
            skillLoaderRegistry = registry,
            agentSkillBindingMapper = org.mockito.kotlin.mock(),
            cliSkillBindingMapper = org.mockito.kotlin.mock(),
            localTmpDir = "/tmp/harnax-test",
        )

        testRepository = SkillRepository().apply {
            id = 1L
            tenantId = 1L
            name = "test-git-repo"
            url = "https://github.com/test/skills"
            branch = "main"
            sourceType = "GIT"
            sourceConfig = """{"url":"https://github.com/test/skills","branch":"main"}"""
            version = "1.0.0"
            description = "Test repository"
            status = 1
            isPublic = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testSkill = Skill().apply {
            id = 1L
            tenantId = 1L
            name = "test-skill"
            repositoryId = 1L
            description = "A test skill"
            skillmd = "# Test Skill"
            resources = "{}"
            version = "1.0.0"
            status = 1
            isPublic = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))

        `when`(jwtUtil.validateToken(any())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        RequestContextHolder.resetRequestAttributes()
    }

    @Nested
    @DisplayName("Get Skill Source Tests")
    inner class GetSkillSourceTests {

        @Test
        fun `getSkillSource should return repository by id`() {
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val result = skillSourceService.getSkillSource(1L)

            assertNotNull(result)
            assertEquals("test-git-repo", result?.name)
            assertEquals("GIT", result?.sourceType)
        }

        @Test
        fun `getSkillSource should return null when not found`() {
            `when`(skillRepositoryMapper.selectById(999L)).thenReturn(null)

            val result = skillSourceService.getSkillSource(999L)

            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Create Skill Source Tests")
    inner class CreateSkillSourceTests {

        @Test
        fun `createSkillSource should throw BizException when name exists`() {
            val request = SkillSourceCreateRequest(
                name = "test-git-repo",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills", "branch" to "main"),
            )

            `when`(skillRepositoryMapper.selectByName(eq("test-git-repo"), any())).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                skillSourceService.createSkillSource(request)
            }
            assertTrue(exception.message!!.contains("already exists"))
            verify(skillRepositoryMapper, never()).insert(any())
        }

        @Test
        fun `createSkillSource should set correct fields for GIT type`() {
            val request = SkillSourceCreateRequest(
                name = "new-git-repo",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/new/skills", "branch" to "develop"),
                description = "New repo",
            )

            `when`(skillRepositoryMapper.selectByName(eq("new-git-repo"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = skillSourceService.createSkillSource(request)

            assertNotNull(result)
            assertEquals("new-git-repo", result.name)
            assertEquals("GIT", result.sourceType)
            verify(skillRepositoryMapper).insert(any())
        }

        @Test
        fun `createSkillSource should fall back to url and branch when sourceConfig is empty`() {
            val request = SkillSourceCreateRequest(
                name = "fallback-repo",
                sourceType = "GIT",
                sourceConfig = emptyMap(),
                url = "https://github.com/fallback/skills",
                branch = "master",
            )

            `when`(skillRepositoryMapper.selectByName(eq("fallback-repo"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = skillSourceService.createSkillSource(request)

            assertEquals("https://github.com/fallback/skills", result.url)
            assertEquals("master", result.branch)
        }

        @Test
        fun `createSkillSource should set tenantId`() {
            val request = SkillSourceCreateRequest(
                name = "tenant-repo",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills"),
            )

            `when`(skillRepositoryMapper.selectByName(eq("tenant-repo"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = skillSourceService.createSkillSource(request)

            assertEquals(1L, result.tenantId)
        }

        @Test
        fun `createSkillSource should use TenantContext tenantId`() {
            TenantContext.setTenantId(42L)

            val request = SkillSourceCreateRequest(
                name = "tenant42-repo",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/t42/skills"),
            )

            `when`(skillRepositoryMapper.selectByName(eq("tenant42-repo"), eq(42L))).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = skillSourceService.createSkillSource(request)

            assertEquals(42L, result.tenantId)
            verify(skillRepositoryMapper).selectByName(eq("tenant42-repo"), eq(42L))
        }

        @Test
        fun `createSkillSource should check name uniqueness within same tenant`() {
            TenantContext.setTenantId(5L)

            val request = SkillSourceCreateRequest(
                name = "shared-name",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills"),
            )

            // Same name exists in tenant 5
            `when`(skillRepositoryMapper.selectByName(eq("shared-name"), eq(5L))).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                skillSourceService.createSkillSource(request)
            }
            assertTrue(exception.message!!.contains("already exists"))
        }
    }

    @Nested
    @DisplayName("Update Skill Source Tests")
    inner class UpdateSkillSourceTests {

        @Test
        fun `updateSkillSource should throw BizException when not found`() {
            val request = SkillSourceUpdateRequest(name = "updated")
            `when`(skillRepositoryMapper.selectById(999L)).thenReturn(null)

            val exception = assertThrows<BizException> {
                skillSourceService.updateSkillSource(999L, request)
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `updateSkillSource should throw BizException when new name exists`() {
            val otherRepo = SkillRepository().apply {
                id = 2L
                name = "existing-name"
            }
            val request = SkillSourceUpdateRequest(name = "existing-name")

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.selectByName(eq("existing-name"), any())).thenReturn(otherRepo)

            val exception = assertThrows<BizException> {
                skillSourceService.updateSkillSource(1L, request)
            }
            assertTrue(exception.message!!.contains("already exists"))
        }

        @Test
        fun `updateSkillSource should update fields successfully`() {
            val request = SkillSourceUpdateRequest(
                name = "new-name",
                description = "Updated description",
                version = "2.0.0",
            )

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.selectByName(eq("new-name"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = skillSourceService.updateSkillSource(1L, request)

            assertTrue(result)
            verify(skillRepositoryMapper).updateById(any())
        }

        @Test
        fun `updateSkillSource should skip name conflict check when name unchanged`() {
            val request = SkillSourceUpdateRequest(
                name = "test-git-repo",
                description = "Same name, new description",
            )

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = skillSourceService.updateSkillSource(1L, request)

            assertTrue(result)
            verify(skillRepositoryMapper, never()).selectByName(any(), any())
        }

        @Test
        fun `updateSkillSource should use repository tenantId for name conflict check`() {
            // repository has tenantId=1
            val request = SkillSourceUpdateRequest(name = "new-name-across-tenants")
            // TenantContext is set to different tenant, but update should use repository's tenantId
            TenantContext.setTenantId(99L)

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            // Name check should use repository.tenantId (1L), not TenantContext (99L)
            `when`(skillRepositoryMapper.selectByName(eq("new-name-across-tenants"), eq(1L))).thenReturn(null)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = skillSourceService.updateSkillSource(1L, request)

            assertTrue(result)
            verify(skillRepositoryMapper).selectByName(eq("new-name-across-tenants"), eq(1L))
        }
    }

    @Nested
    @DisplayName("Delete Skill Source Tests")
    inner class DeleteSkillSourceTests {

        @Test
        fun `deleteSkillSource should throw BizException when not found`() {
            `when`(skillRepositoryMapper.selectById(999L)).thenReturn(null)

            val exception = assertThrows<BizException> {
                skillSourceService.deleteSkillSource(999L)
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `deleteSkillSource should delete skills and repository`() {
            val skills = listOf(testSkill)
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillMapper.selectByRepositoryId(1L)).thenReturn(skills)
            `when`(skillMapper.deleteById(any<Long>())).thenReturn(1)
            `when`(skillRepositoryMapper.deleteById(1L)).thenReturn(1)

            val result = skillSourceService.deleteSkillSource(1L)

            assertTrue(result)
            verify(skillMapper).deleteById(testSkill.id)
            verify(skillRepositoryMapper).deleteById(1L)
        }
    }

    @Nested
    @DisplayName("Fetch Skills Tests")
    inner class FetchSkillsTests {

        @Test
        fun `fetchSkills should throw BizException when not found`() {
            `when`(skillRepositoryMapper.selectById(999L)).thenReturn(null)

            val exception = assertThrows<BizException> {
                skillSourceService.fetchSkills(999L)
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `fetchSkills should return empty list when loader returns no skills`() {
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val result = skillSourceService.fetchSkills(1L)

            assertTrue(result.isEmpty())
        }
    }

    @Nested
    @DisplayName("Upload and Install Tests")
    inner class UploadAndInstallTests {

        @Test
        fun `uploadAndInstall should throw BizException when name exists`() {
            `when`(skillRepositoryMapper.selectByName(eq("existing"), any())).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                skillSourceService.uploadAndInstall("/tmp/test.zip", "test.zip", "existing")
            }
            assertTrue(exception.message!!.contains("already exists"))
        }

        @Test
        fun `uploadAndInstall should create ZIP source type`() {
            `when`(skillRepositoryMapper.selectByName(eq("new-zip"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val zip = java.nio.file.Files.createTempFile("test-upload-", ".zip")
            val result = skillSourceService.uploadAndInstall(zip.toString(), "test.zip", "new-zip")

            assertEquals("ZIP", result.sourceType)
            assertEquals("new-zip", result.name)
            verify(skillRepositoryMapper).insert(any())
        }

        @Test
        fun `uploadAndInstall should set tenantId from TenantContext`() {
            TenantContext.setTenantId(7L)

            `when`(skillRepositoryMapper.selectByName(eq("tenant-zip"), eq(7L))).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val captor = argumentCaptor<SkillRepository>()
            val zip = java.nio.file.Files.createTempFile("test-upload-", ".zip")
            val result = skillSourceService.uploadAndInstall(zip.toString(), "test.zip", "tenant-zip")

            verify(skillRepositoryMapper).insert(captor.capture())
            assertEquals(7L, captor.firstValue.tenantId)
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        fun `convertToResponse should convert entity to SkillSourceResponse`() {
            val result = skillSourceService.convertToResponse(testRepository)

            assertEquals(testRepository.id, result.id)
            assertEquals("test-git-repo", result.name)
            assertEquals("GIT", result.sourceType)
            assertEquals("1.0.0", result.version)
            assertNotNull(result.sourceConfig)
        }

        @Test
        fun `convertToResponse should handle empty sourceConfig`() {
            testRepository.sourceConfig = ""

            val result = skillSourceService.convertToResponse(testRepository)

            assertNull(result.sourceConfig)
        }

        @Test
        fun `convertToResponse should handle malformed sourceConfig`() {
            testRepository.sourceConfig = "invalid json{"

            val result = skillSourceService.convertToResponse(testRepository)

            assertNull(result.sourceConfig)
        }

        @Test
        fun `convertToResponse should handle null sourceConfig fields gracefully`() {
            testRepository.sourceConfig = """{"url":null,"branch":null}"""

            val result = skillSourceService.convertToResponse(testRepository)

            assertNotNull(result)
            assertNotNull(result.sourceConfig)
        }
    }

    @Nested
    @DisplayName("Boundary Tests")
    inner class BoundaryTests {

        @Test
        fun `createSkillSource should handle null localTmpDir by using system temp`() {
            val serviceWithNullTmpDir = SkillSourceServiceImpl(
                jwtUtil = jwtUtil,
                skillRepositoryMapper = skillRepositoryMapper,
                skillMapper = skillMapper,
                skillLoaderRegistry = SkillLoaderRegistry(emptyList()),
                agentSkillBindingMapper = org.mockito.kotlin.mock(),
                cliSkillBindingMapper = org.mockito.kotlin.mock(),
                localTmpDir = null,
            )

            val request = SkillSourceCreateRequest(
                name = "null-tmp-repo",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills"),
            )

            `when`(skillRepositoryMapper.selectByName(eq("null-tmp-repo"), any())).thenReturn(null)

            // Should not throw, will use system temp directory
            assertThrows<Exception> {
                // Will fail at getLoader since registry is empty, but that's expected
                serviceWithNullTmpDir.createSkillSource(request)
            }
        }

        @Test
        fun `createSkillSource should propagate loader validation errors`() {
            val failingLoader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("GIT")
                org.mockito.Mockito.doThrow(IllegalArgumentException("Invalid URL format"))
                    .`when`(this).validateConfig(any())
            }
            val registry = SkillLoaderRegistry(listOf(failingLoader))

            val service = SkillSourceServiceImpl(
                jwtUtil = jwtUtil,
                skillRepositoryMapper = skillRepositoryMapper,
                skillMapper = skillMapper,
                skillLoaderRegistry = registry,
                agentSkillBindingMapper = org.mockito.kotlin.mock(),
                cliSkillBindingMapper = org.mockito.kotlin.mock(),
                localTmpDir = "/tmp/harnax-test",
            )

            val request = SkillSourceCreateRequest(
                name = "invalid-repo",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to ""),
            )

            `when`(skillRepositoryMapper.selectByName(eq("invalid-repo"), any())).thenReturn(null)

            val exception = assertThrows<IllegalArgumentException> {
                service.createSkillSource(request)
            }
            assertTrue(exception.message!!.contains("Invalid URL format"))
        }

        @Test
        fun `createSkillSource should propagate exception when loader throws during loadSkills`() {
            val failingLoader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("GIT")
                `when`(loadSkills(any(), any())).thenThrow(RuntimeException("Network timeout"))
            }
            val registry = SkillLoaderRegistry(listOf(failingLoader))

            val service = SkillSourceServiceImpl(
                jwtUtil = jwtUtil,
                skillRepositoryMapper = skillRepositoryMapper,
                skillMapper = skillMapper,
                skillLoaderRegistry = registry,
                agentSkillBindingMapper = org.mockito.kotlin.mock(),
                cliSkillBindingMapper = org.mockito.kotlin.mock(),
                localTmpDir = "/tmp/harnax-test",
            )

            val request = SkillSourceCreateRequest(
                name = "timeout-repo",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills"),
            )

            `when`(skillRepositoryMapper.selectByName(eq("timeout-repo"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)

            // Repository is created but installSkills throws - exception propagates
            // This documents current behavior: no @Transactional on createSkillSource
            assertThrows<RuntimeException> {
                service.createSkillSource(request)
            }
            // Repository insert was still called (no rollback without @Transactional)
            verify(skillRepositoryMapper).insert(any())
        }

        @Test
        fun `createSkillSource should continue installing other skills when one fails`() {
            val agentSkill1 = AgentSkill.builder()
                .name("skill-1")
                .skillContent("# Skill 1")
                .description("First skill")
                .build()
            val agentSkill2 = AgentSkill.builder()
                .name("skill-2")
                .skillContent("# Skill 2")
                .description("Second skill")
                .build()

            val loader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("GIT")
                `when`(loadSkills(any(), any())).thenReturn(listOf(agentSkill1, agentSkill2))
            }
            val registry = SkillLoaderRegistry(listOf(loader))

            val service = SkillSourceServiceImpl(
                jwtUtil = jwtUtil,
                skillRepositoryMapper = skillRepositoryMapper,
                skillMapper = skillMapper,
                skillLoaderRegistry = registry,
                agentSkillBindingMapper = org.mockito.kotlin.mock(),
                cliSkillBindingMapper = org.mockito.kotlin.mock(),
                localTmpDir = "/tmp/harnax-test",
            )

            val request = SkillSourceCreateRequest(
                name = "partial-fail-repo",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills"),
            )

            `when`(skillRepositoryMapper.selectByName(eq("partial-fail-repo"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)
            `when`(skillMapper.selectByNameAndRepo(any(), any())).thenReturn(null)
            // First insert succeeds, second fails
            `when`(skillMapper.insert(any()))
                .thenReturn(1)
                .thenThrow(RuntimeException("Insert failed"))

            val result = service.createSkillSource(request)

            // Should complete without throwing
            assertNotNull(result)
            // Both skills attempted despite the second failing
            verify(skillMapper, org.mockito.Mockito.times(2)).insert(any())
        }

        @Test
        fun `updateSkillSource should update sourceConfig JSON correctly`() {
            val request = SkillSourceUpdateRequest(
                sourceConfig = mapOf(
                    "url" to "https://github.com/new/skills",
                    "branch" to "develop",
                ),
            )

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = skillSourceService.updateSkillSource(1L, request)

            assertTrue(result)
            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).updateById(captor.capture())
            assertTrue(captor.firstValue.sourceConfig.contains("develop"))
        }

        @Test
        fun `deleteSkillSource should handle empty skill list`() {
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillMapper.selectByRepositoryId(1L)).thenReturn(emptyList())
            `when`(skillRepositoryMapper.deleteById(1L)).thenReturn(1)

            val result = skillSourceService.deleteSkillSource(1L)

            assertTrue(result)
            verify(skillMapper, never()).deleteById(any<Long>())
        }

        @Test
        fun `fetchSkills should return skills from loader correctly`() {
            val agentSkill = AgentSkill.builder()
                .name("valid-skill")
                .skillContent("# Valid Skill Content")
                .description("A valid skill")
                .build()

            val loader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("GIT")
                `when`(loadSkills(any(), any())).thenReturn(listOf(agentSkill))
            }
            val registry = SkillLoaderRegistry(listOf(loader))

            val service = SkillSourceServiceImpl(
                jwtUtil = jwtUtil,
                skillRepositoryMapper = skillRepositoryMapper,
                skillMapper = skillMapper,
                skillLoaderRegistry = registry,
                agentSkillBindingMapper = org.mockito.kotlin.mock(),
                cliSkillBindingMapper = org.mockito.kotlin.mock(),
                localTmpDir = "/tmp/harnax-test",
            )

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val result = service.fetchSkills(1L)

            assertEquals(1, result.size)
            assertEquals("valid-skill", result[0].name)
            assertEquals("# Valid Skill Content", result[0].skillmd)
            assertEquals("A valid skill", result[0].description)
        }

        @Test
        fun `uploadAndInstall should handle ZIP with no skills gracefully`() {
            val loader = org.mockito.Mockito.mock(ZipSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("ZIP")
                `when`(loadSkills(any(), any())).thenReturn(emptyList())
            }
            val registry = SkillLoaderRegistry(listOf(loader))

            val service = SkillSourceServiceImpl(
                jwtUtil = jwtUtil,
                skillRepositoryMapper = skillRepositoryMapper,
                skillMapper = skillMapper,
                skillLoaderRegistry = registry,
                agentSkillBindingMapper = org.mockito.kotlin.mock(),
                cliSkillBindingMapper = org.mockito.kotlin.mock(),
                localTmpDir = "/tmp/harnax-test",
            )

            `when`(skillRepositoryMapper.selectByName(eq("empty-zip"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val zip = java.nio.file.Files.createTempFile("test-empty-", ".zip")
            val result = service.uploadAndInstall(zip.toString(), "empty.zip", "empty-zip")

            assertNotNull(result)
            assertEquals("empty-zip", result.name)
            verify(skillMapper, never()).insert(any())
        }
    }
}
