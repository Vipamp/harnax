package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SkillRepositoryCreateRequest
import com.agnetix.harnax.admin.dto.SkillRepositoryUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.skill.SkillInstaller
import com.agnetix.harnax.admin.skill.loader.SkillLoadResult
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.SkillRepository
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
 * Unit tests for SkillRepositoryServiceImpl.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillRepositoryServiceImplTest {

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    @Mock
    private lateinit var skillMapper: com.agnetix.harnax.mapper.SkillMapper

    @Mock
    private lateinit var skillLoaderRegistry: com.agnetix.harnax.admin.skill.loader.SkillLoaderRegistry

    @Mock
    private lateinit var skillInstaller: SkillInstaller

    private lateinit var service: SkillRepositoryServiceImpl

    private lateinit var testRepository: SkillRepository

    @BeforeEach
    fun setUp() {
        service = SkillRepositoryServiceImpl(
            jwtUtil = jwtUtil,
            skillRepositoryMapper = skillRepositoryMapper,
            skillMapper = skillMapper,
            skillLoaderRegistry = skillLoaderRegistry,
            skillInstaller = skillInstaller,
            localTmpDir = "/tmp/harnax-test",
        )

        // Creating a repository validates its Git config too now, and the registry is a mock: the
        // tests that care about validation hand in their own loader, the rest just need one back
        `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(
            org.mockito.Mockito.mock(com.agnetix.harnax.admin.skill.loader.SkillLoader::class.java),
        )

        testRepository = SkillRepository().apply {
            id = 1L
            tenantId = 1L
            name = "test-repo"
            url = "https://github.com/test/skills"
            branch = "main"
            sourceType = "GIT"
            description = "Test repository"
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
    @DisplayName("Create Repository Tests")
    inner class CreateRepositoryTests {

        @Test
        fun `createSkillRepository should throw BizException when name exists in same tenant`() {
            TenantContext.setTenantId(1L)
            val request = SkillRepositoryCreateRequest(
                name = "test-repo",
                url = "https://github.com/test/skills",
                branch = "main",
            )

            `when`(skillRepositoryMapper.selectByName(eq("test-repo"), eq(1L))).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                service.createSkillRepository(request)
            }
            assertTrue(exception.message!!.contains("already exists"))
            verify(skillRepositoryMapper, never()).insert(any())
        }

        @Test
        fun `createSkillRepository should succeed when name exists in different tenant`() {
            TenantContext.setTenantId(2L)
            val request = SkillRepositoryCreateRequest(
                name = "test-repo",
                url = "https://github.com/tenant2/skills",
                branch = "main",
            )

            // Same name doesn't exist in tenant 2
            `when`(skillRepositoryMapper.selectByName(eq("test-repo"), eq(2L))).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)

            val result = service.createSkillRepository(request)

            assertTrue(result)
            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).insert(captor.capture())
            assertEquals(2L, captor.firstValue.tenantId)
            assertEquals("test-repo", captor.firstValue.name)
        }

        @Test
        fun `createSkillRepository should set tenantId from TenantContext`() {
            TenantContext.setTenantId(42L)
            val request = SkillRepositoryCreateRequest(
                name = "new-repo",
                url = "https://github.com/t42/skills",
                branch = "main",
            )

            `when`(skillRepositoryMapper.selectByName(eq("new-repo"), eq(42L))).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)

            val result = service.createSkillRepository(request)

            assertTrue(result)
            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).insert(captor.capture())
            assertEquals(42L, captor.firstValue.tenantId)
        }

        @Test
        fun `createSkillRepository should default to tenantId 1 when context is null`() {
            // TenantContext not set (null)
            val request = SkillRepositoryCreateRequest(
                name = "default-tenant-repo",
                url = "https://github.com/test/skills",
                branch = "main",
            )

            `when`(skillRepositoryMapper.selectByName(eq("default-tenant-repo"), eq(1L))).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)

            val result = service.createSkillRepository(request)

            assertTrue(result)
            verify(skillRepositoryMapper).selectByName(eq("default-tenant-repo"), eq(1L))
        }

        @Test
        fun `createSkillRepository should stay private and describe itself as a GIT source`() {
            val request = SkillRepositoryCreateRequest(
                name = "described-repo",
                url = "https://github.com/test/skills",
                branch = "",
            )

            `when`(skillRepositoryMapper.selectByName(eq("described-repo"), eq(1L))).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)

            assertTrue(service.createSkillRepository(request))

            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).insert(captor.capture())
            // Publishing was never part of this endpoint, so the row must not be public by accident
            assertEquals(0, captor.firstValue.isPublic)
            assertEquals("GIT", captor.firstValue.sourceType)
            assertTrue(captor.firstValue.sourceConfig.contains("\"branch\":\"main\""))
        }

        @Test
        fun `createSkillRepository should reject a url the loader would refuse`() {
            // 创建时不校验的话，仓库会先建成功，等到第一次同步才回一句「Unsupported Git URL」，
            // 报错离真正的原因隔了一整个请求；updateSkillRepository 一直是当场拒的
            val loader = org.mockito.Mockito.mock(com.agnetix.harnax.admin.skill.loader.SkillLoader::class.java)
            org.mockito.Mockito.doThrow(IllegalArgumentException("Unsupported Git URL"))
                .`when`(loader).validateConfig(any())
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(loader)

            val request = SkillRepositoryCreateRequest(
                name = "bad-url-repo",
                url = "file:///etc/passwd",
                branch = "main",
            )
            `when`(skillRepositoryMapper.selectByName(eq("bad-url-repo"), eq(1L))).thenReturn(null)

            val exception = assertThrows<IllegalArgumentException> {
                service.createSkillRepository(request)
            }
            assertTrue(exception.message!!.contains("Unsupported Git URL"))
            verify(skillRepositoryMapper, never()).insert(any())
        }

        @Test
        fun `createSkillRepository should store one canonical url in both places`() {
            // CLI 传的 URL 常带空格。loader 会 trim，但旧列和 JSON 配置各存一份未归一化的值时，
            // 列表页显示一个没人输入过的地址，两边还可能不一致
            val request = SkillRepositoryCreateRequest(
                name = "padded-repo",
                url = "  https://github.com/test/skills  ",
                branch = "  release/1.0  ",
            )
            `when`(skillRepositoryMapper.selectByName(eq("padded-repo"), eq(1L))).thenReturn(null)
            `when`(skillRepositoryMapper.insert(any())).thenReturn(1)

            assertTrue(service.createSkillRepository(request))

            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).insert(captor.capture())
            val stored = captor.firstValue
            assertEquals("https://github.com/test/skills", stored.url)
            assertEquals("release/1.0", stored.branch)
            assertTrue(stored.sourceConfig.contains("\"url\":\"https://github.com/test/skills\""))
            assertTrue(stored.sourceConfig.contains("\"branch\":\"release/1.0\""))
        }

        @Test
        fun `createSkillRepository should reject an out-of-range status before touching the database`() {
            // status 存进 TINYINT 不报错，而仓库列表与技能下发都用 status == 1 判断，存个 7
            // 等于造出一个永远无法启用的仓库
            val request = SkillRepositoryCreateRequest(
                name = "bad-status-repo",
                url = "https://github.com/test/skills",
                status = 7,
            )

            val exception = assertThrows<BizException> {
                service.createSkillRepository(request)
            }

            assertTrue(exception.message!!.contains("Status must be 0"))
            verify(skillRepositoryMapper, never()).selectByName(any(), anyLong())
            verify(skillRepositoryMapper, never()).insert(any())
        }
    }

    @Nested
    @DisplayName("Update Repository Tests")
    inner class UpdateRepositoryTests {

        @Test
        fun `updateSkillRepository should throw BizException when not found`() {
            `when`(skillRepositoryMapper.selectById(999L)).thenReturn(null)

            val request = SkillRepositoryUpdateRequest(name = "updated")
            val exception = assertThrows<BizException> {
                service.updateSkillRepository(999L, request)
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `updateSkillRepository should reject a repository owned by another tenant`() {
            // repository has tenantId=1, the caller belongs to tenant 99
            TenantContext.setTenantId(99L)
            val request = SkillRepositoryUpdateRequest(name = "new-name")

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                service.updateSkillRepository(1L, request)
            }
            assertTrue(exception.message!!.contains("another tenant"))
            verify(skillRepositoryMapper, never()).updateById(any())
        }

        @Test
        fun `updateSkillRepository should use repository tenantId for name conflict check`() {
            // No tenant context means an internal call, so the repository's own tenant wins
            val request = SkillRepositoryUpdateRequest(name = "new-name")

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            // Should use repository.tenantId (1L), not the caller's
            `when`(skillRepositoryMapper.selectByName(eq("new-name"), eq(1L))).thenReturn(null)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = service.updateSkillRepository(1L, request)

            assertTrue(result)
            verify(skillRepositoryMapper).selectByName(eq("new-name"), eq(1L))
        }

        @Test
        fun `updateSkillRepository should skip name check when name unchanged`() {
            val request = SkillRepositoryUpdateRequest(
                name = "test-repo",
                description = "Updated desc",
            )

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            val result = service.updateSkillRepository(1L, request)

            assertTrue(result)
            verify(skillRepositoryMapper, never()).selectByName(any(), any())
        }

        @Test
        fun `updateSkillRepository should keep sourceConfig in sync with the new url`() {
            // Given
            val loader = org.mockito.Mockito.mock(com.agnetix.harnax.admin.skill.loader.SkillLoader::class.java)
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(loader)
            testRepository.sourceConfig = """{"url":"https://github.com/test/skills","branch":"main"}"""
            val request = SkillRepositoryUpdateRequest(url = "https://github.com/test/moved")

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            // When
            assertTrue(service.updateSkillRepository(1L, request))

            // Then
            val captor = argumentCaptor<SkillRepository>()
            verify(skillRepositoryMapper).updateById(captor.capture())
            // fetchRemoteSkills 先读 sourceConfig 再读 url 列，只改列会让「改地址」看起来成功，
            // 而下一次同步仍然克隆旧仓库
            assertEquals("https://github.com/test/moved", captor.firstValue.url)
            assertTrue(captor.firstValue.sourceConfig.contains("https://github.com/test/moved"))
            assertFalse(captor.firstValue.sourceConfig.contains("test/skills"))
            // 新地址要过一遍传输协议白名单，不能等到同步时才在克隆里报错
            verify(loader).validateConfig(any())
        }

        @Test
        fun `updateSkillRepository should reject a url outside the transport whitelist`() {
            // Given
            val loader = org.mockito.Mockito.mock(com.agnetix.harnax.admin.skill.loader.SkillLoader::class.java)
            org.mockito.Mockito.doThrow(IllegalArgumentException("Unsupported Git transport"))
                .`when`(loader).validateConfig(any())
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(loader)
            val request = SkillRepositoryUpdateRequest(url = "file:///etc/passwd")

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            // When & Then
            val exception = assertThrows<IllegalArgumentException> {
                service.updateSkillRepository(1L, request)
            }
            assertTrue(exception.message!!.contains("Unsupported Git transport"))
            verify(skillRepositoryMapper, never()).updateById(any())
        }

        @Test
        fun `updateSkillRepository should route a status change to the dedicated update statement`() {
            // updateById 的 SQL 里没有 status 列，而 DTO 一直声明了这个字段。之前不读它，
            // `harnax skill-repo update <id> --status 0` 就会报成功而库里没变
            val request = SkillRepositoryUpdateRequest(status = 0)

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.updateStatus(1L, 0)).thenReturn(1)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            assertTrue(service.updateSkillRepository(1L, request))

            verify(skillRepositoryMapper).updateStatus(1L, 0)
        }

        @Test
        fun `updateSkillRepository should leave status alone when the request omits it`() {
            val request = SkillRepositoryUpdateRequest(description = "Updated description")

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.updateById(any())).thenReturn(1)

            assertTrue(service.updateSkillRepository(1L, request))

            verify(skillRepositoryMapper, never()).updateStatus(anyLong(), anyInt())
        }

        @Test
        fun `updateSkillRepository should reject an out-of-range status`() {
            val request = SkillRepositoryUpdateRequest(status = 7)

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                service.updateSkillRepository(1L, request)
            }

            assertTrue(exception.message!!.contains("Status must be 0"))
            verify(skillRepositoryMapper, never()).updateStatus(anyLong(), anyInt())
            verify(skillRepositoryMapper, never()).updateById(any())
        }
    }

    @Nested
    @DisplayName("Get Active Repositories Tests")
    inner class GetActiveRepositoriesTests {

        @Test
        fun `getActiveRepositories should filter by tenantId`() {
            TenantContext.setTenantId(1L)
            `when`(skillRepositoryMapper.selectActiveRepositories(eq(1L))).thenReturn(listOf(testRepository))

            val result = service.getActiveRepositories()

            assertEquals(1, result.size)
            assertEquals(1L, result[0].tenantId)
            verify(skillRepositoryMapper).selectActiveRepositories(eq(1L))
        }

        @Test
        fun `getActiveRepositories should default to tenantId 1 when context is null`() {
            `when`(skillRepositoryMapper.selectActiveRepositories(eq(1L))).thenReturn(listOf(testRepository))

            val result = service.getActiveRepositories()

            verify(skillRepositoryMapper).selectActiveRepositories(eq(1L))
        }
    }

    @Nested
    @DisplayName("Get By Name Tests")
    inner class GetByNameTests {

        @Test
        fun `getByName should use tenantId from context`() {
            TenantContext.setTenantId(3L)
            `when`(skillRepositoryMapper.selectByName(eq("test-repo"), eq(3L))).thenReturn(testRepository)

            val result = service.getByName("test-repo")

            assertNotNull(result)
            verify(skillRepositoryMapper).selectByName(eq("test-repo"), eq(3L))
        }
    }

    @Nested
    @DisplayName("Get Repository By Id Tests")
    inner class GetSkillRepositoryTests {

        @Test
        fun `getSkillRepository should not answer with another tenant's repository`() {
            // 旧版详情接口一直不做租户校验，一个租户能读到另一个租户的仓库，而 Git URL
            // 里可能嵌着克隆用的 token
            TenantContext.setTenantId(99L)
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                service.getSkillRepository(1L)
            }
            assertTrue(exception.message!!.contains("another tenant"))
        }

        @Test
        fun `getSkillRepository should still return the shared builtin repository`() {
            // 内置仓库由平台播种给某一个租户、所有租户共用，按租户挡掉会让技能列表和绑定框全空
            TenantContext.setTenantId(99L)
            val builtin = SkillRepository().apply {
                id = 2L
                tenantId = 1L
                name = com.agnetix.harnax.admin.constant.BuiltinRepository.CLI_SKILLS
            }
            `when`(skillRepositoryMapper.selectById(2L)).thenReturn(builtin)

            assertEquals(
                com.agnetix.harnax.admin.constant.BuiltinRepository.CLI_SKILLS,
                service.getSkillRepository(2L)?.name,
            )
        }

        @Test
        fun `getSkillRepository should skip the tenant check for an internal call`() {
            // 没有租户上下文说明是内部调用（定时任务、路由服务），这时不能把仓库挡掉
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            assertEquals("test-repo", service.getSkillRepository(1L)?.name)
        }
    }

    @Nested
    @DisplayName("Toggle and Delete Tests")
    inner class ToggleAndDeleteTests {

        @Test
        fun `toggleSkillRepository should throw when not found`() {
            `when`(skillRepositoryMapper.selectById(999L)).thenReturn(null)

            assertThrows<BizException> {
                service.toggleSkillRepository(999L, 0)
            }
        }

        @Test
        fun `toggleSkillRepository should update status`() {
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillRepositoryMapper.updateStatus(1L, 0)).thenReturn(1)

            val result = service.toggleSkillRepository(1L, 0)

            assertTrue(result)
            verify(skillRepositoryMapper).updateStatus(1L, 0)
        }

        @Test
        fun `toggleSkillRepository should reject a status outside 0 and 1`() {
            // status 是两态开关，写进 99 会让这一行永远显示为禁用，且 UI 上的开关再也拉不回来
            val exception = assertThrows<BizException> {
                service.toggleSkillRepository(1L, 99)
            }

            assertTrue(exception.message!!.contains("0 (disabled) or 1 (enabled)"))
            verify(skillRepositoryMapper, never()).selectById(any())
        }

        @Test
        fun `deleteSkillRepository should cascade to its skills`() {
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val result = service.deleteSkillRepository(1L)

            assertTrue(result)
            verify(skillInstaller).deleteWithSkills(testRepository)
        }

        @Test
        fun `deleteSkillRepository should return false when not found`() {
            `when`(skillRepositoryMapper.selectById(999L)).thenReturn(null)

            assertFalse(service.deleteSkillRepository(999L))
            verify(skillInstaller, never()).deleteWithSkills(any())
        }

        @Test
        fun `deleteSkillRepository should refuse the builtin repository`() {
            val builtin = SkillRepository().apply {
                id = 2L
                tenantId = 1L
                name = "builtin-cli-skills"
                sourceType = "BUILTIN"
            }
            `when`(skillRepositoryMapper.selectById(2L)).thenReturn(builtin)

            val exception = assertThrows<BizException> {
                service.deleteSkillRepository(2L)
            }
            assertTrue(exception.message!!.contains("read-only"))
            verify(skillInstaller, never()).deleteWithSkills(any())
        }
    }

    @Nested
    @DisplayName("Builtin Repository Tests")
    inner class BuiltinRepositoryTests {

        @Test
        fun `getBuiltinRepository should resolve the shared row without a tenant filter`() {
            val builtin = SkillRepository().apply {
                id = 2L
                tenantId = 1L
                name = "builtin-cli-skills"
                sourceType = "BUILTIN"
            }
            TenantContext.setTenantId(99L)
            `when`(skillRepositoryMapper.selectBuiltinRepository(eq("builtin-cli-skills"))).thenReturn(builtin)

            val result = service.getBuiltinRepository()

            assertEquals(2L, result?.id)
            verify(skillRepositoryMapper).selectBuiltinRepository(eq("builtin-cli-skills"))
        }

        @Test
        fun `fetchRemoteSkills should reject a repository owned by another tenant`() {
            TenantContext.setTenantId(99L)
            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)

            val exception = assertThrows<BizException> {
                service.fetchRemoteSkills(1L)
            }
            assertTrue(exception.message!!.contains("another tenant"))
        }

        @Test
        fun `fetchRemoteSkills should reject a ZIP source that keeps no archive`() {
            val zipRepo = SkillRepository().apply {
                id = 3L
                tenantId = 1L
                name = "uploaded-zip"
                sourceType = "ZIP"
            }
            `when`(skillRepositoryMapper.selectById(3L)).thenReturn(zipRepo)

            val exception = assertThrows<BizException> {
                service.fetchRemoteSkills(3L)
            }
            assertTrue(exception.message!!.contains("installed once"))
        }

        @Test
        fun `fetchRemoteSkills should report the name the way it will be stored`() {
            // 预览显示的名字必须就是落库用的那一个：SkillInstaller 存的是 trim 之后的值，
            // 回显原始值会让带空格的名字既显示成「新增」，选回去又匹配不上
            val padded = io.agentscope.core.skill.AgentSkill.builder()
                .name("  padded-skill  ")
                .description("Declared with padding")
                .skillContent("# Padded")
                .build()
            val loader = org.mockito.Mockito.mock(com.agnetix.harnax.admin.skill.loader.SkillLoader::class.java)
            `when`(loader.loadSkills(any(), any())).thenReturn(SkillLoadResult(listOf(padded)))
            `when`(skillLoaderRegistry.getLoader("GIT")).thenReturn(loader)

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            `when`(skillMapper.selectByRepositoryId(1L)).thenReturn(
                listOf(
                    com.agnetix.harnax.entity.Skill().apply {
                        id = 7L
                        name = "padded-skill"
                        repositoryId = 1L
                    },
                ),
            )

            val result = service.fetchRemoteSkills(1L)

            assertEquals(1, result.size)
            assertEquals("padded-skill", result[0].name)
            // 库里已经有 trim 之后的那一行，预览必须认得出是「已存在」
            assertTrue(result[0].exists)
        }
    }
}
