package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillRepositoryCreateRequest
import com.agnetix.harnax.admin.dto.SkillRepositoryUpdateRequest
import com.agnetix.harnax.admin.dto.SyncSkillResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.entity.SkillRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * SkillRepositoryController 单元测试
 * 直接实例化 Controller + Mock Service，断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillRepositoryControllerTest {

    @Mock
    private lateinit var skillRepositoryService: SkillRepositoryService

    @InjectMocks
    private lateinit var controller: SkillRepositoryController

    private lateinit var testRepository: SkillRepository

    @BeforeEach
    fun setUp() {
        testRepository = SkillRepository().apply {
            id = 1L
            tenantId = 1L
            name = "qoder-skills"
            url = "https://github.com/example/skills"
            branch = "main"
            sourceType = "GIT"
            sourceConfig = """{"url":"https://github.com/example/skills","branch":"main"}"""
            description = "Test repository"
            status = 1
            isPublic = 0
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
    }

    @Nested
    @DisplayName("GET /api/admin/skill-repositories/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageSkillRepository - 返回分页结果")
        fun `pageSkillRepository should return paginated results`() {
            val page = Page<SkillRepository>(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testRepository))
            `when`(skillRepositoryService.page(null, null, 1, 10)).thenReturn(page)

            val result = controller.pageSkillRepository(1, 10, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("qoder-skills", result.data?.records?.get(0)?.name)
        }

        @Test
        @DisplayName("pageSkillRepository - 传递过滤条件")
        fun `pageSkillRepository should pass filters correctly`() {
            val page = Page<SkillRepository>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(skillRepositoryService.page("qoder", 1, 1, 10)).thenReturn(page)

            val result = controller.pageSkillRepository(1, 10, "qoder", 1)

            assertTrue(result.isSuccess())
            verify(skillRepositoryService).page("qoder", 1, 1, 10)
        }

        @Test
        @DisplayName("pageSkillRepository - pageNum/pageSize 为 null 时使用默认值")
        fun `pageSkillRepository should use default paging when null`() {
            val page = Page<SkillRepository>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(skillRepositoryService.page(null, null, 1, 10)).thenReturn(page)

            val result = controller.pageSkillRepository(null, null, null, null)

            assertTrue(result.isSuccess())
            verify(skillRepositoryService).page(null, null, 1, 10)
        }

        @Test
        @DisplayName("pageSkillRepository - service 抛异常返回 error")
        fun `pageSkillRepository should return error when service throws`() {
            `when`(skillRepositoryService.page(null, null, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.pageSkillRepository(1, 10, null, null)

            assertFalse(result.isSuccess())
            assertEquals(500, result.code)
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/skill-repositories/active")
    inner class ActiveEndpoint {

        @Test
        @DisplayName("getActiveRepositories - 返回激活仓库列表")
        fun `getActiveRepositories should return active list`() {
            `when`(skillRepositoryService.getActiveRepositories()).thenReturn(listOf(testRepository))

            val result = controller.getActiveRepositories()

            assertTrue(result.isSuccess())
            assertEquals(1, result.data?.size)
            assertEquals("qoder-skills", result.data?.get(0)?.name)
        }

        @Test
        @DisplayName("getActiveRepositories - 无激活仓库时返回空列表")
        fun `getActiveRepositories should return empty list when none`() {
            `when`(skillRepositoryService.getActiveRepositories()).thenReturn(emptyList())

            val result = controller.getActiveRepositories()

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("getActiveRepositories - service 抛异常返回 error")
        fun `getActiveRepositories should return error when service throws`() {
            `when`(skillRepositoryService.getActiveRepositories()).thenThrow(RuntimeException("DB error"))

            val result = controller.getActiveRepositories()

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/skill-repositories/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getSkillRepository - 返回仓库详情")
        fun `getSkillRepository should return repository details`() {
            `when`(skillRepositoryService.getSkillRepository(1L)).thenReturn(testRepository)

            val result = controller.getSkillRepository(1L)

            assertTrue(result.isSuccess())
            assertEquals("qoder-skills", result.data?.name)
            assertEquals("GIT", result.data?.sourceType)
        }

        @Test
        @DisplayName("getSkillRepository - 不存在时 data 为 null")
        fun `getSkillRepository should return null data when not found`() {
            `when`(skillRepositoryService.getSkillRepository(999L)).thenReturn(null)

            val result = controller.getSkillRepository(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getSkillRepository - service 抛异常返回 error")
        fun `getSkillRepository should return error when service throws`() {
            `when`(skillRepositoryService.getSkillRepository(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getSkillRepository(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/skill-repositories")
    inner class CreateEndpoint {

        @Test
        @DisplayName("createRepository - 创建成功")
        fun `createRepository should return success`() {
            val request = SkillRepositoryCreateRequest(
                name = "new-repo",
                url = "https://github.com/new/skills",
                branch = "main",
            )
            `when`(skillRepositoryService.createSkillRepository(any())).thenReturn(true)

            val result = controller.createRepository(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("createRepository - service 返回 false 时返回 error")
        fun `createRepository should return error when service returns false`() {
            val request = SkillRepositoryCreateRequest(name = "new-repo")
            `when`(skillRepositoryService.createSkillRepository(any())).thenReturn(false)

            val result = controller.createRepository(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create skill repository", result.message)
        }

        @Test
        @DisplayName("createRepository - 名称重复时返回 error")
        fun `createRepository should return error on duplicate name`() {
            val request = SkillRepositoryCreateRequest(name = "qoder-skills")
            `when`(skillRepositoryService.createSkillRepository(any()))
                .thenThrow(BizException("Repository name already exists"))

            val result = controller.createRepository(request)

            assertFalse(result.isSuccess())
            assertEquals("Repository name already exists", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/skill-repositories/update/{id}")
    inner class UpdateEndpoint {

        @Test
        @DisplayName("updateSkillRepository - 更新成功")
        fun `updateSkillRepository should return success`() {
            val request = SkillRepositoryUpdateRequest(description = "Updated description")
            `when`(skillRepositoryService.updateSkillRepository(any(), any())).thenReturn(true)

            val result = controller.updateSkillRepository(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateSkillRepository - service 返回 false 时返回 error")
        fun `updateSkillRepository should return error when service returns false`() {
            val request = SkillRepositoryUpdateRequest(description = "Updated description")
            `when`(skillRepositoryService.updateSkillRepository(any(), any())).thenReturn(false)

            val result = controller.updateSkillRepository(1L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update skill repository", result.message)
        }

        @Test
        @DisplayName("updateSkillRepository - 仓库不存在时返回 error")
        fun `updateSkillRepository should return error when not found`() {
            val request = SkillRepositoryUpdateRequest(description = "Updated description")
            `when`(skillRepositoryService.updateSkillRepository(any(), any()))
                .thenThrow(BizException("Skill repository not found"))

            val result = controller.updateSkillRepository(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Skill repository not found", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/skill-repositories/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleSkillRepository - 切换状态成功")
        fun `toggleSkillRepository should return success`() {
            `when`(skillRepositoryService.toggleSkillRepository(1L, 0)).thenReturn(true)

            val result = controller.toggleSkillRepository(1L, 0)

            assertTrue(result.isSuccess())
            verify(skillRepositoryService).toggleSkillRepository(1L, 0)
        }

        @Test
        @DisplayName("toggleSkillRepository - service 返回 false 时返回 error")
        fun `toggleSkillRepository should return error when service returns false`() {
            `when`(skillRepositoryService.toggleSkillRepository(1L, 1)).thenReturn(false)

            val result = controller.toggleSkillRepository(1L, 1)

            assertFalse(result.isSuccess())
            // 该端点是切换状态，文案不能沿用 update 那句复制过来的
            assertEquals("Failed to toggle skill repository status", result.message)
        }

        @Test
        @DisplayName("toggleSkillRepository - service 抛异常返回 error")
        fun `toggleSkillRepository should return error when service throws`() {
            `when`(skillRepositoryService.toggleSkillRepository(999L, 1))
                .thenThrow(BizException("Skill repository not found"))

            val result = controller.toggleSkillRepository(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Skill repository not found", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/skill-repositories/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteSkillRepository - 删除成功")
        fun `deleteSkillRepository should return success`() {
            `when`(skillRepositoryService.deleteSkillRepository(1L)).thenReturn(true)

            val result = controller.deleteSkillRepository(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("deleteSkillRepository - service 返回 false 时返回 error")
        fun `deleteSkillRepository should return error when service returns false`() {
            `when`(skillRepositoryService.deleteSkillRepository(1L)).thenReturn(false)

            val result = controller.deleteSkillRepository(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete skill repository", result.message)
        }

        @Test
        @DisplayName("deleteSkillRepository - 仓库不存在时返回 error")
        fun `deleteSkillRepository should return error when not found`() {
            `when`(skillRepositoryService.deleteSkillRepository(999L))
                .thenThrow(BizException("Skill repository not found"))

            val result = controller.deleteSkillRepository(999L)

            assertFalse(result.isSuccess())
            assertEquals("Skill repository not found", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/skill-repositories/fetch/{id}")
    inner class FetchRemoteSkillsEndpoint {

        @Test
        @DisplayName("fetchRemoteSkills - 返回远程技能列表")
        fun `fetchRemoteSkills should return skill list`() {
            val skills = listOf(
                SyncSkillResponse(name = "skill-a", description = "Skill A", skillmd = "# Skill A"),
                SyncSkillResponse(name = "skill-b", description = "Skill B", skillmd = "# Skill B"),
            )
            `when`(skillRepositoryService.fetchRemoteSkills(1L)).thenReturn(skills)

            val result = controller.fetchRemoteSkills(1L)

            assertTrue(result.isSuccess())
            assertEquals(2, result.data?.size)
            assertEquals("skill-a", result.data?.get(0)?.name)
        }

        @Test
        @DisplayName("fetchRemoteSkills - 无技能时返回空列表")
        fun `fetchRemoteSkills should return empty list when none`() {
            `when`(skillRepositoryService.fetchRemoteSkills(1L)).thenReturn(emptyList())

            val result = controller.fetchRemoteSkills(1L)

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("fetchRemoteSkills - 拉取失败时返回 error")
        fun `fetchRemoteSkills should return error when fetch fails`() {
            `when`(skillRepositoryService.fetchRemoteSkills(1L)).thenThrow(RuntimeException("Git clone failed"))

            val result = controller.fetchRemoteSkills(1L)

            assertFalse(result.isSuccess())
            assertEquals("Git clone failed", result.message)
        }
    }
}
