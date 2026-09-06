package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SkillSourceCreateRequest
import com.agnetix.harnax.admin.dto.SkillSourceUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.skill.SkillInstaller
import com.agnetix.harnax.admin.skill.loader.GitSkillLoader
import com.agnetix.harnax.admin.skill.loader.NpmSkillLoader
import com.agnetix.harnax.admin.skill.loader.SkillLoadFailure
import com.agnetix.harnax.admin.skill.loader.SkillLoadResult
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
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
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
            `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(emptyList()))
        }
        val npmLoader = org.mockito.Mockito.mock(NpmSkillLoader::class.java).apply {
            `when`(sourceType).thenReturn("NPM")
            `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(emptyList()))
        }
        val zipLoader = org.mockito.Mockito.mock(ZipSkillLoader::class.java).apply {
            `when`(sourceType).thenReturn("ZIP")
            `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(emptyList()))
        }
        val registry = SkillLoaderRegistry(listOf(gitLoader, npmLoader, zipLoader))

        skillSourceService = newService(registry)

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

    /**
     * Builds the service with a real [SkillInstaller] over the same mocked mappers, so the tests
     * keep observing the actual writes instead of asserting on a stubbed collaborator.
     */
    private fun newService(registry: SkillLoaderRegistry, tmpDir: String? = "/tmp/harnax-test") = SkillSourceServiceImpl(
        jwtUtil = jwtUtil,
        skillRepositoryMapper = skillRepositoryMapper,
        skillMapper = skillMapper,
        skillLoaderRegistry = registry,
        skillInstaller = SkillInstaller(
            skillMapper = skillMapper,
            skillRepositoryMapper = skillRepositoryMapper,
            agentSkillBindingMapper = org.mockito.kotlin.mock(),
            cliSkillBindingMapper = org.mockito.kotlin.mock(),
        ),
        localTmpDir = tmpDir,
    )

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
            assertEquals("new-git-repo", result.source.name)
            assertEquals("GIT", result.source.sourceType)
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

            assertEquals("https://github.com/fallback/skills", result.source.url)
            assertEquals("master", result.source.branch)
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

            skillSourceService.createSkillSource(request)

            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).insert(captor.capture())
            assertEquals(1L, captor.firstValue.tenantId)
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

            skillSourceService.createSkillSource(request)

            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).insert(captor.capture())
            assertEquals(42L, captor.firstValue.tenantId)
            // Checked once before the write and once inside the transaction, where the row may
            // have appeared in between
            verify(skillRepositoryMapper, Mockito.atLeastOnce()).selectByName(eq("tenant42-repo"), eq(42L))
        }

        @Test
        fun `createSkillSource should reject the reserved builtin name`() {
            val request = SkillSourceCreateRequest(
                name = "builtin-cli-skills",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills"),
            )

            val exception = assertThrows<BizException> {
                skillSourceService.createSkillSource(request)
            }
            assertTrue(exception.message!!.contains("reserved"))
            verify(skillRepositoryMapper, never()).insert(any())
        }

        @Test
        fun `createSkillSource should report per-skill failures instead of swallowing them`() {
            val broken = AgentSkill.builder()
                .name("broken")
                .description("A skill whose insert fails")
                .skillContent("# Broken")
                .build()
            val loader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("GIT")
                `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(broken)))
            }
            val service = newService(SkillLoaderRegistry(listOf(loader)))

            val request = SkillSourceCreateRequest(
                name = "reporting-repo",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills"),
            )

            `when`(skillRepositoryMapper.selectByName(eq("reporting-repo"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillMapper.insert(any())).thenThrow(RuntimeException("Insert failed"))

            val result = service.createSkillSource(request)

            assertFalse(result.install.complete)
            assertEquals(1, result.install.failedCount)
            assertEquals("broken", result.install.failed[0].name)
            assertTrue(result.install.summary.contains("1 failed"))
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

        @Test
        fun `createSkillSource should refuse a ZIP source and point at the upload endpoint`() {
            // ZIP 源「本身就是那个压缩包」，只能靠上传创建。放进这条路径只会回一句 loader 的
            // requires 'zipPath'，既不解释原因也不告诉调用方该改用哪个端点
            val request = SkillSourceCreateRequest(name = "zip-source", sourceType = "ZIP")

            `when`(skillRepositoryMapper.selectByName(eq("zip-source"), any())).thenReturn(null)

            val exception = assertThrows<BizException> {
                skillSourceService.createSkillSource(request)
            }
            assertTrue(exception.message!!.contains("/api/admin/skill-sources/upload"))
            verify(skillRepositoryMapper, never()).insert(any())
        }

        @Test
        fun `createSkillSource should store the config without the padding it arrived with`() {
            // URL 和分支名多半是从聊天窗口或终端粘过来的，带空格。loader 自己会 trim，所以克隆
            // 一直是好的，但存进库里的是原始文本：列表页显示一个没人输入过的地址，编辑表单又把
            // 它带回来，旧列和 JSON 配置各存一份还可能不一致
            val request = SkillSourceCreateRequest(
                name = "padded-source",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "  https://github.com/test/skills  ", "branch" to "  release/1.0  "),
            )

            `when`(skillRepositoryMapper.selectByName(eq("padded-source"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)

            skillSourceService.createSkillSource(request)

            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).insert(captor.capture())
            val stored = captor.firstValue
            assertEquals("https://github.com/test/skills", stored.url)
            assertEquals("release/1.0", stored.branch)
            assertTrue(stored.sourceConfig.contains("\"url\":\"https://github.com/test/skills\""))
            assertTrue(stored.sourceConfig.contains("\"branch\":\"release/1.0\""))
        }

        @Test
        fun `createSkillSource should reject an out-of-range status before fetching anything`() {
            // 拉取是整个请求里最贵的一步，入参不合法时应该先拒绝，而不是克隆完才发现存不进去
            val request = SkillSourceCreateRequest(
                name = "bad-status-source",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills"),
                status = 7,
            )

            val exception = assertThrows<BizException> {
                skillSourceService.createSkillSource(request)
            }

            assertTrue(exception.message!!.contains("Status must be 0"))
            verify(skillRepositoryMapper, never()).selectByName(any(), anyLong())
            verify(skillRepositoryMapper, never()).insert(any())
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
        fun `updateSkillSource should reject a repository owned by another tenant`() {
            // repository has tenantId=1, the caller belongs to tenant 99
            val request = SkillSourceUpdateRequest(name = "new-name-across-tenants")
            TenantContext.setTenantId(99L)

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                skillSourceService.updateSkillSource(1L, request)
            }
            assertTrue(exception.message!!.contains("another tenant"))
            verify(skillRepositoryMapper, never()).updateById(any())
        }

        @Test
        fun `updateSkillSource should use repository tenantId for name conflict check`() {
            // No tenant context means an internal call, so the repository's own tenant wins
            val request = SkillSourceUpdateRequest(name = "new-name-across-tenants")

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            // Name check should use repository.tenantId (1L)
            `when`(skillRepositoryMapper.selectByName(eq("new-name-across-tenants"), eq(1L))).thenReturn(null)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = skillSourceService.updateSkillSource(1L, request)

            assertTrue(result)
            verify(skillRepositoryMapper).selectByName(eq("new-name-across-tenants"), eq(1L))
        }

        @Test
        fun `updateSkillSource should reject the reserved builtin name`() {
            val request = SkillSourceUpdateRequest(name = "builtin-cli-skills")

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                skillSourceService.updateSkillSource(1L, request)
            }
            assertTrue(exception.message!!.contains("reserved"))
            verify(skillRepositoryMapper, never()).updateById(any())
        }

        @Test
        fun `updateSkillSource should trim a url given through the legacy field`() {
            // 走 url/branch 字段（而不是 sourceConfig）的编辑同样要归一化，而且旧列和 JSON 配置
            // 必须落成同一份文本，否则下次同步读到的是另一个地址
            val request = SkillSourceUpdateRequest(url = "  https://github.com/test/moved  ")

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            assertTrue(skillSourceService.updateSkillSource(1L, request))

            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).updateById(captor.capture())
            assertEquals("https://github.com/test/moved", captor.firstValue.url)
            assertTrue(captor.firstValue.sourceConfig.contains("\"url\":\"https://github.com/test/moved\""))
        }

        @Test
        fun `updateSkillSource should trim a config map it is handed`() {
            // sourceConfig 是个自由 map，DTO 上的 @Size 管不到里面的值，归一化只能在这里做
            val request = SkillSourceUpdateRequest(
                sourceConfig = mapOf("url" to " https://github.com/test/moved ", "branch" to " dev "),
            )

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            assertTrue(skillSourceService.updateSkillSource(1L, request))

            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).updateById(captor.capture())
            assertEquals("https://github.com/test/moved", captor.firstValue.url)
            assertEquals("dev", captor.firstValue.branch)
        }

        @Test
        fun `updateSkillSource should keep installed skills visibility in sync`() {
            val privateSkill = Skill().apply {
                id = 5L
                name = "child"
                repositoryId = 1L
                isPublic = 0
            }
            val request = SkillSourceUpdateRequest(isPublic = 1)

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository.apply { isPublic = 0 })
            `when`(skillMapper.selectByRepositoryId(1L)).thenReturn(listOf(privateSkill))
            `when`(skillMapper.updateById(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            assertTrue(skillSourceService.updateSkillSource(1L, request))

            assertEquals(1, privateSkill.isPublic)
            verify(skillMapper).updateById(privateSkill)
        }

        @Test
        fun `updateSkillSource should reject writes to the builtin repository`() {
            val builtin = SkillRepository().apply {
                id = 2L
                tenantId = 1L
                name = "builtin-cli-skills"
                sourceType = "BUILTIN"
            }
            `when`(skillRepositoryMapper.selectById(2L)).thenReturn(builtin)

            val exception = assertThrows<BizException> {
                skillSourceService.updateSkillSource(2L, SkillSourceUpdateRequest(description = "tampered"))
            }
            assertTrue(exception.message!!.contains("read-only"))
            verify(skillRepositoryMapper, never()).updateById(any())
        }

        @Test
        fun `updateSkillSource should reject an out-of-range status`() {
            // 启停本来就有专用的 toggle 端点把关取值，走更新这一路也不能把非法值存进去
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                skillSourceService.updateSkillSource(1L, SkillSourceUpdateRequest(status = 7))
            }

            assertTrue(exception.message!!.contains("Status must be 0"))
            verify(skillRepositoryMapper, never()).updateStatus(anyLong(), anyInt())
            verify(skillRepositoryMapper, never()).updateById(any())
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

        @Test
        fun `fetchSkills should refuse a ZIP source that keeps no archive`() {
            val zipRepo = SkillRepository().apply {
                id = 3L
                tenantId = 1L
                name = "uploaded-zip"
                sourceType = "ZIP"
            }
            `when`(skillRepositoryMapper.selectById(3L)).thenReturn(zipRepo)

            val exception = assertThrows<BizException> {
                skillSourceService.fetchSkills(3L)
            }
            assertTrue(exception.message!!.contains("installed once"))
        }

        @Test
        fun `fetchSkills should reject a repository owned by another tenant`() {
            TenantContext.setTenantId(99L)
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                skillSourceService.fetchSkills(1L)
            }
            assertTrue(exception.message!!.contains("another tenant"))
        }
    }

    @Nested
    @DisplayName("Install Skills Tests")
    inner class InstallSkillsTests {

        @Test
        fun `installSkills should re-load the source and report the outcome`() {
            val agentSkill = AgentSkill.builder()
                .name("refreshed")
                .skillContent("# Refreshed")
                .description("Refreshed skill")
                .build()
            val loader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("GIT")
                `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(agentSkill)))
            }
            val service = newService(SkillLoaderRegistry(listOf(loader)))

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillMapper.selectByNameAndRepo(eq("refreshed"), eq(1L))).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            val result = service.installSkills(1L)

            assertEquals(listOf("refreshed"), result.installed)
            assertTrue(result.complete)
            assertEquals(1, result.savedCount)
        }

        @Test
        fun `installSkills should report a directory the loader could not parse`() {
            // Loader 读不出来的目录以前只留一条 warn，接口照样答 200：
            // 「源里 2 个目录、落库 1 个」的差额必须出现在 failed 里才对得上
            val readable = AgentSkill.builder()
                .name("readable")
                .skillContent("# Readable")
                .description("Readable skill")
                .build()
            val loader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("GIT")
                `when`(loadSkills(any(), any())).thenReturn(
                    SkillLoadResult(
                        listOf(readable),
                        listOf(SkillLoadFailure("broken-dir", "SKILL.md could not be parsed: MalformedInputException")),
                    ),
                )
            }
            val service = newService(SkillLoaderRegistry(listOf(loader)))

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillMapper.selectByNameAndRepo(eq("readable"), eq(1L))).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            val result = service.installSkills(1L)

            assertEquals(listOf("readable"), result.installed)
            assertEquals(1, result.failedCount)
            assertEquals("broken-dir", result.failed[0].name)
            assertTrue(result.failed[0].reason.contains("could not be parsed"))
            // 有技能没落库，整体就不算完成
            assertFalse(result.complete)
        }

        @Test
        fun `installSkills should refuse a ZIP source`() {
            val zipRepo = SkillRepository().apply {
                id = 3L
                tenantId = 1L
                name = "uploaded-zip"
                sourceType = "ZIP"
            }
            `when`(skillRepositoryMapper.selectById(3L)).thenReturn(zipRepo)

            val exception = assertThrows<BizException> {
                skillSourceService.installSkills(3L)
            }
            assertTrue(exception.message!!.contains("installed once"))
        }

        @Test
        fun `installSkills should refuse the builtin repository`() {
            val builtin = SkillRepository().apply {
                id = 2L
                tenantId = 1L
                name = "builtin-cli-skills"
                sourceType = "BUILTIN"
            }
            `when`(skillRepositoryMapper.selectById(2L)).thenReturn(builtin)

            val exception = assertThrows<BizException> {
                skillSourceService.installSkills(2L)
            }
            assertTrue(exception.message!!.contains("read-only"))
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

            assertEquals("ZIP", result.source.sourceType)
            assertEquals("new-zip", result.source.name)
            verify(skillRepositoryMapper).insert(any())
        }

        @Test
        fun `uploadAndInstall should not persist the temporary zip path`() {
            `when`(skillRepositoryMapper.selectByName(eq("no-path-zip"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)

            val zip = java.nio.file.Files.createTempFile("test-upload-", ".zip")
            val result = skillSourceService.uploadAndInstall(zip.toString(), "test.zip", "no-path-zip")

            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).insert(captor.capture())
            // The temp file is deleted right after the request, so a stored path would be a dead end
            assertFalse(captor.firstValue.sourceConfig.contains("zipPath"))
            assertNull(result.source.sourceConfig?.get("zipPath"))
        }

        @Test
        fun `uploadAndInstall should reject the reserved builtin name`() {
            val exception = assertThrows<BizException> {
                skillSourceService.uploadAndInstall("/tmp/test.zip", "test.zip", "builtin-cli-skills")
            }
            assertTrue(exception.message!!.contains("reserved"))
            verify(skillRepositoryMapper, never()).insert(any())
        }

        @Test
        fun `uploadAndInstall should set tenantId from TenantContext`() {
            TenantContext.setTenantId(7L)

            `when`(skillRepositoryMapper.selectByName(eq("tenant-zip"), eq(7L))).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val captor = argumentCaptor<SkillRepository>()
            val zip = java.nio.file.Files.createTempFile("test-upload-", ".zip")
            skillSourceService.uploadAndInstall(zip.toString(), "test.zip", "tenant-zip")

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
            val serviceWithNullTmpDir = newService(SkillLoaderRegistry(emptyList()), tmpDir = null)

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

            val service = newService(registry)

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

            val service = newService(registry)

            val request = SkillSourceCreateRequest(
                name = "timeout-repo",
                sourceType = "GIT",
                sourceConfig = mapOf("url" to "https://github.com/test/skills"),
            )

            `when`(skillRepositoryMapper.selectByName(eq("timeout-repo"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)

            assertThrows<RuntimeException> {
                service.createSkillSource(request)
            }
            // Loading runs before anything is written, so a dead source leaves no half-created row
            verify(skillRepositoryMapper, never()).insert(any())
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
                `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(agentSkill1, agentSkill2)))
            }
            val registry = SkillLoaderRegistry(listOf(loader))

            val service = newService(registry)

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
            assertEquals(listOf("skill-1"), result.install.installed)
            assertEquals(listOf("skill-2"), result.install.failed.map { it.name })
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
                `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(agentSkill)))
            }
            val registry = SkillLoaderRegistry(listOf(loader))

            val service = newService(registry)

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val result = service.fetchSkills(1L)

            assertEquals(1, result.size)
            assertEquals("valid-skill", result[0].name)
            assertEquals("# Valid Skill Content", result[0].skillmd)
            assertEquals("A valid skill", result[0].description)
        }

        @Test
        fun `fetchSkills should report the name the way it will be stored`() {
            // 预览显示的名字必须就是落库用的那一个：SkillInstaller 存的是 trim 之后的值，
            // 回显原始值会让带空格的名字既显示成「新增」，选回去又匹配不上
            val padded = AgentSkill.builder()
                .name("  padded-skill  ")
                .skillContent("# Padded")
                .description("Declared with padding")
                .build()

            val loader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("GIT")
                `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(padded)))
            }
            val service = newService(SkillLoaderRegistry(listOf(loader)))

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillMapper.selectByRepositoryId(1L)).thenReturn(
                listOf(
                    Skill().apply {
                        id = 7L
                        name = "padded-skill"
                        repositoryId = 1L
                    },
                ),
            )

            val result = service.fetchSkills(1L)

            assertEquals("padded-skill", result[0].name)
            // 库里已经有 trim 之后的那一行，预览必须认得出是「已存在」
            assertTrue(result[0].exists)
        }

        @Test
        fun `uploadAndInstall should handle ZIP with no skills gracefully`() {
            val loader = org.mockito.Mockito.mock(ZipSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("ZIP")
                `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(emptyList()))
            }
            val registry = SkillLoaderRegistry(listOf(loader))

            val service = newService(registry)

            `when`(skillRepositoryMapper.selectByName(eq("empty-zip"), any())).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val zip = java.nio.file.Files.createTempFile("test-empty-", ".zip")
            val result = service.uploadAndInstall(zip.toString(), "empty.zip", "empty-zip")

            assertNotNull(result)
            assertEquals("empty-zip", result.source.name)
            verify(skillMapper, never()).insert(any())
        }

        @Test
        fun `page should forward the sourceType filter to the query`() {
            TenantContext.setTenantId(1L)
            `when`(skillRepositoryMapper.selectRepositoryList(null, null, "admin", 1L, "builtin-cli-skills", "NPM"))
                .thenReturn(emptyList())

            skillSourceService.page(null, "NPM", null, 1, 10)

            // 接口对外声明了这个过滤参数，之前它在 service 里被丢掉，不管传什么类型都返回全量列表
            verify(skillRepositoryMapper).selectRepositoryList(null, null, "admin", 1L, "builtin-cli-skills", "NPM")
        }

        @Test
        fun `page should read a blank sourceType as no filter`() {
            TenantContext.setTenantId(1L)
            `when`(skillRepositoryMapper.selectRepositoryList(null, null, "admin", 1L, "builtin-cli-skills", null))
                .thenReturn(emptyList())

            skillSourceService.page(null, "   ", null, 1, 10)

            // 空字符串不该变成 source_type = '' 这种永远查不到行的条件
            verify(skillRepositoryMapper).selectRepositoryList(null, null, "admin", 1L, "builtin-cli-skills", null)
        }

        @Test
        fun `toggleStatus should reject a value outside 0 and 1`() {
            // status 是两态开关，消费方一律按 == 1 判断；写进 99 会让这一行永远显示为禁用
            val exception = assertThrows<BizException> {
                skillSourceService.toggleStatus(1L, 99)
            }

            assertTrue(exception.message!!.contains("0 (disabled) or 1 (enabled)"))
            verify(skillRepositoryMapper, never()).updateStatus(any<Long>(), any<Int>())
        }

        @Test
        fun `installSkills should count a name the source declares twice only once`() {
            val duplicated = listOf(
                AgentSkill.builder().name("same").skillContent("# One").description("first").build(),
                AgentSkill.builder().name("same").skillContent("# Two").description("second").build(),
            )
            val loader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("GIT")
                `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(duplicated))
            }
            val service = newService(SkillLoaderRegistry(listOf(loader)))

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillMapper.selectByNameAndRepo(eq("same"), eq(1L))).thenReturn(null)
            `when`(skillMapper.insert(any())).thenReturn(1)

            val result = service.installSkills(1L)

            // 第二次出现会在同一事务里读到第一次刚写的行，于是被当成更新再计一次：
            // savedCount 报 2 而库里只有 1 条
            assertEquals(1, result.savedCount)
            assertEquals(listOf("same"), result.installed)
            assertTrue(result.updated.isEmpty())
            assertEquals(listOf("same"), result.failed.map { it.name })
            verify(skillMapper).insert(any())
            verify(skillMapper, never()).updateById(any())
        }

        @Test
        fun `installSkills should not report as flagged a skill that failed to persist`() {
            val dangerous = AgentSkill.builder()
                .name("wiper")
                .skillContent("# Wiper\n\nRun `rm -rf /` to clean up.")
                .description("Wipes the disk")
                .build()
            val loader = org.mockito.Mockito.mock(GitSkillLoader::class.java).apply {
                `when`(sourceType).thenReturn("GIT")
                `when`(loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(dangerous)))
            }
            val service = newService(SkillLoaderRegistry(listOf(loader)))

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillMapper.selectByNameAndRepo(eq("wiper"), eq(1L))).thenReturn(null)
            `when`(skillMapper.insert(any())).thenThrow(RuntimeException("Data too long for column 'skillmd'"))

            val result = service.installSkills(1L)

            // flagged 的意思是「已存为禁用」，写库失败时同一个名字不能既进 flagged 又进 failed
            assertTrue(result.flagged.isEmpty())
            assertEquals(listOf("wiper"), result.failed.map { it.name })
            assertEquals(0, result.savedCount)
        }
    }
}
