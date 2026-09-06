package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillCreateRequest
import com.agnetix.harnax.admin.dto.SkillInstallResponse
import com.agnetix.harnax.admin.dto.SkillResponse
import com.agnetix.harnax.admin.dto.SkillUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.entity.Skill
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
 * SkillController 单元测试
 * 直接实例化 Controller + Mock Service，断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillControllerTest {

    @Mock
    private lateinit var skillService: SkillService

    @Mock
    private lateinit var skillRepositoryService: SkillRepositoryService

    @InjectMocks
    private lateinit var controller: SkillController

    private lateinit var testSkill: Skill
    private lateinit var testRepository: SkillRepository
    private lateinit var testResponse: SkillResponse

    @BeforeEach
    fun setUp() {
        testSkill = Skill().apply {
            id = 1L
            tenantId = 1L
            name = "test-skill"
            repositoryId = 10L
            description = "Test skill"
            skillmd = "# Test Skill"
            status = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testRepository = SkillRepository().apply {
            id = 10L
            name = "qoder-skills"
            url = "https://github.com/example/skills"
            branch = "main"
        }

        testResponse = SkillResponse(
            id = 1L,
            name = "test-skill",
            repositoryId = 10L,
            repositoryName = "qoder-skills",
            description = "Test skill",
            status = 1,
        )
    }

    @Nested
    @DisplayName("GET /api/admin/skills/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageSkill - 返回分页结果")
        fun `pageSkill should return paginated results`() {
            val page = Page<Skill>(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testSkill))
            `when`(skillService.page(null, null, null, 1, 10)).thenReturn(page)
            `when`(skillService.convertToResponses(listOf(testSkill))).thenReturn(listOf(testResponse))

            val result = controller.pageSkill(1, 10, null, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("test-skill", result.data?.records?.get(0)?.name)
        }

        @Test
        @DisplayName("pageSkill - 传递过滤条件")
        fun `pageSkill should pass filters correctly`() {
            val page = Page<Skill>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(skillService.page("test", 10L, 1, 1, 10)).thenReturn(page)
            `when`(skillService.convertToResponses(emptyList())).thenReturn(emptyList())

            val result = controller.pageSkill(1, 10, "test", 10L, 1)

            assertTrue(result.isSuccess())
            verify(skillService).page("test", 10L, 1, 1, 10)
        }

        @Test
        @DisplayName("pageSkill - pageNum/pageSize 为 null 时使用默认值")
        fun `pageSkill should use default paging when null`() {
            val page = Page<Skill>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(skillService.page(null, null, null, 1, 10)).thenReturn(page)
            `when`(skillService.convertToResponses(emptyList())).thenReturn(emptyList())

            val result = controller.pageSkill(null, null, null, null, null)

            assertTrue(result.isSuccess())
            verify(skillService).page(null, null, null, 1, 10)
        }

        @Test
        @DisplayName("pageSkill - service 抛异常返回 error")
        fun `pageSkill should return error when service throws`() {
            `when`(skillService.page(null, null, null, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.pageSkill(1, 10, null, null, null)

            assertFalse(result.isSuccess())
            assertEquals(500, result.code)
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/skills/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getSkill - 返回技能详情")
        fun `getSkill should return skill details`() {
            `when`(skillService.getSkill(1L)).thenReturn(testSkill)
            `when`(skillRepositoryService.getSkillRepository(10L)).thenReturn(testRepository)

            val result = controller.getSkill(1L)

            assertTrue(result.isSuccess())
            assertEquals("test-skill", result.data?.name)
            assertEquals("qoder-skills", result.data?.repositoryName)
        }

        @Test
        @DisplayName("getSkill - 不存在时 data 为 null")
        fun `getSkill should return null data when not found`() {
            `when`(skillService.getSkill(999L)).thenReturn(null)

            val result = controller.getSkill(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getSkill - service 抛异常返回 error")
        fun `getSkill should return error when service throws`() {
            `when`(skillService.getSkill(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getSkill(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/skills")
    inner class CreateEndpoint {

        @Test
        @DisplayName("createSkill - 创建成功")
        fun `createSkill should return success`() {
            val request = SkillCreateRequest(name = "new-skill", repositoryId = 10L)
            `when`(skillService.createSkill(any())).thenReturn(true)

            val result = controller.createSkill(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("createSkill - service 返回 false 时返回 error")
        fun `createSkill should return error when service returns false`() {
            val request = SkillCreateRequest(name = "new-skill", repositoryId = 10L)
            `when`(skillService.createSkill(any())).thenReturn(false)

            val result = controller.createSkill(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create skill", result.message)
        }

        @Test
        @DisplayName("createSkill - 名称重复时返回 error")
        fun `createSkill should return error on duplicate name`() {
            val request = SkillCreateRequest(name = "test-skill", repositoryId = 10L)
            `when`(skillService.createSkill(any())).thenThrow(BizException("Skill name already exists"))

            val result = controller.createSkill(request)

            assertFalse(result.isSuccess())
            assertEquals("Skill name already exists", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/skills/update/{id}")
    inner class UpdateEndpoint {

        @Test
        @DisplayName("updateSkill - 更新成功")
        fun `updateSkill should return success`() {
            val request = SkillUpdateRequest(description = "Updated description")
            `when`(skillService.updateSkill(any(), any())).thenReturn(true)

            val result = controller.updateSkill(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateSkill - service 返回 false 时返回 error")
        fun `updateSkill should return error when service returns false`() {
            val request = SkillUpdateRequest(description = "Updated description")
            `when`(skillService.updateSkill(any(), any())).thenReturn(false)

            val result = controller.updateSkill(1L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update skill", result.message)
        }

        @Test
        @DisplayName("updateSkill - 技能不存在时返回 error")
        fun `updateSkill should return error when not found`() {
            val request = SkillUpdateRequest(description = "Updated description")
            `when`(skillService.updateSkill(any(), any())).thenThrow(BizException("Skill not found"))

            val result = controller.updateSkill(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Skill not found", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/skills/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleSkill - 切换状态成功")
        fun `toggleSkill should return success`() {
            `when`(skillService.toggleSkillStatus(1L, 0)).thenReturn(true)

            val result = controller.toggleSkill(1L, 0)

            assertTrue(result.isSuccess())
            verify(skillService).toggleSkillStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleSkill - service 返回 false 时返回 error")
        fun `toggleSkill should return error when service returns false`() {
            `when`(skillService.toggleSkillStatus(1L, 1)).thenReturn(false)

            val result = controller.toggleSkill(1L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle skill status", result.message)
        }

        @Test
        @DisplayName("toggleSkill - service 抛异常返回 error")
        fun `toggleSkill should return error when service throws`() {
            `when`(skillService.toggleSkillStatus(999L, 1)).thenThrow(BizException("Skill not found"))

            val result = controller.toggleSkill(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Skill not found", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/skills/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteSkill - 删除成功")
        fun `deleteSkill should return success`() {
            `when`(skillService.deleteSkill(1L)).thenReturn(true)

            val result = controller.deleteSkill(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("deleteSkill - service 返回 false 时返回 error")
        fun `deleteSkill should return error when service returns false`() {
            `when`(skillService.deleteSkill(1L)).thenReturn(false)

            val result = controller.deleteSkill(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete skill", result.message)
        }

        @Test
        @DisplayName("deleteSkill - 技能不存在时返回 error")
        fun `deleteSkill should return error when not found`() {
            `when`(skillService.deleteSkill(999L)).thenThrow(BizException("Skill not found"))

            val result = controller.deleteSkill(999L)

            assertFalse(result.isSuccess())
            assertEquals("Skill not found", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/skills/batch")
    inner class BatchSaveEndpoint {

        @Test
        @DisplayName("batchSaveSkills - 批量保存成功返回明细结果")
        fun `batchSaveSkills should return saved count`() {
            val skills = listOf("skill-a", "skill-b")
            `when`(skillService.batchSaveSkillsDetailed(10L, skills))
                .thenReturn(SkillInstallResponse(installed = listOf("skill-a", "skill-b")))

            val result = controller.batchSaveSkills(10L, skills)

            assertTrue(result.isSuccess())
            assertEquals(2, result.data?.savedCount)
            assertTrue(result.data?.complete == true)
        }

        @Test
        @DisplayName("batchSaveSkills - 部分失败时把失败清单回传前端")
        fun `batchSaveSkills should surface partial failures`() {
            val skills = listOf("skill-a", "skill-b")
            `when`(skillService.batchSaveSkillsDetailed(10L, skills)).thenReturn(
                SkillInstallResponse(
                    installed = listOf("skill-a"),
                    failed = listOf(SkillInstallResponse.FailedSkill("skill-b", "Not present in the source anymore")),
                ),
            )

            val result = controller.batchSaveSkills(10L, skills)

            // 接口仍然 200，但失败明细必须可见，不能再静默丢失
            assertTrue(result.isSuccess())
            assertEquals(1, result.data?.savedCount)
            assertTrue(result.data?.complete == false)
            assertEquals(listOf("skill-b"), result.data?.failed?.map { it.name })
        }

        @Test
        @DisplayName("batchSaveSkills - 空列表时返回空结果")
        fun `batchSaveSkills should return zero for empty list`() {
            `when`(skillService.batchSaveSkillsDetailed(10L, emptyList())).thenReturn(SkillInstallResponse())

            val result = controller.batchSaveSkills(10L, emptyList())

            assertTrue(result.isSuccess())
            assertEquals(0, result.data?.savedCount)
        }

        @Test
        @DisplayName("batchSaveSkills - service 抛异常返回 error")
        fun `batchSaveSkills should return error when service throws`() {
            `when`(skillService.batchSaveSkillsDetailed(any(), any())).thenThrow(BizException("Repository not found"))

            val result = controller.batchSaveSkills(999L, listOf("skill-a"))

            assertFalse(result.isSuccess())
            assertEquals("Repository not found", result.message)
        }
    }
}
