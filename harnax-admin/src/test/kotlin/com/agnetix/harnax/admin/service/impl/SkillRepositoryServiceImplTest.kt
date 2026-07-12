package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SkillRepositoryCreateRequest
import com.agnetix.harnax.admin.dto.SkillRepositoryUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
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

    private lateinit var service: SkillRepositoryServiceImpl

    private lateinit var testRepository: SkillRepository

    @BeforeEach
    fun setUp() {
        service = SkillRepositoryServiceImpl(
            jwtUtil = jwtUtil,
            skillRepositoryMapper = skillRepositoryMapper,
            localTmpDir = "/tmp/harnax-test",
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
        fun `updateSkillRepository should use repository tenantId for name conflict check`() {
            TenantContext.setTenantId(99L)
            val request = SkillRepositoryUpdateRequest(name = "new-name")

            `when`(skillRepositoryMapper.selectById(1L)).thenReturn(testRepository)
            // Should use repository.tenantId (1L), not TenantContext (99L)
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
        fun `deleteSkillRepository should soft delete`() {
            `when`(skillRepositoryMapper.deleteById(1L)).thenReturn(1)

            val result = service.deleteSkillRepository(1L)

            assertTrue(result)
        }
    }
}
