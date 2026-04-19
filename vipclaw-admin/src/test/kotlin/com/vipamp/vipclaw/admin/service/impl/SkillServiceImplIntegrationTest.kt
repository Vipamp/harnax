package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.SkillCreateRequest
import com.vipamp.vipclaw.admin.dto.SkillUpdateRequest
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SkillMapper
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SkillServiceImpl 集成测试
 * 使用 Testcontainers 启动真实的 MySQL 容器进行测试
 *
 * @author vipamp
 * @since 2026-04-20
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SkillServiceImplIntegrationTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("vipclaw_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var skillService: SkillServiceImpl

    @Autowired
    private lateinit var skillMapper: SkillMapper

    @Nested
    @DisplayName("分页查询测试")
    inner class PaginationTests {

        @Test
        @DisplayName("getSkillPage - 正常分页查询")
        fun `getSkillPage should return paginated results`() {
            // When
            val page = skillService.getSkillPage(null, null, null, 1, 2)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 2) // schema-test.sql 中有3条，但deleted的active=0
            assertEquals(2, page.size)
            assertEquals(1, page.current)
            assertEquals(2, page.records.size)
        }

        @Test
        @DisplayName("getSkillPage - 名称搜索")
        fun `getSkillPage should filter by name`() {
            // When
            val page = skillService.getSkillPage("web-search", null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.name.contains("web-search") })
        }

        @Test
        @DisplayName("getSkillPage - 仓库ID过滤")
        fun `getSkillPage should filter by repositoryId`() {
            // When
            val page = skillService.getSkillPage(null, 1L, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 2)
            assertTrue(page.records.all { it.repositoryId == 1L })
        }

        @Test
        @DisplayName("getSkillPage - 状态过滤")
        fun `getSkillPage should filter by status`() {
            // When
            val page = skillService.getSkillPage(null, null, 1, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 2)
            assertTrue(page.records.all { it.status == 1 })
        }
    }

    @Nested
    @DisplayName("查询技能详情测试")
    inner class GetSkillByIdTests {

        @Test
        @DisplayName("getSkillById - 查询存在的技能")
        fun `getSkillById should return skill when exists`() {
            // When
            val skill = skillService.getSkillById(1L)

            // Then
            assertNotNull(skill)
            assertEquals(1L, skill?.id)
            assertEquals("web-search", skill?.name)
        }

        @Test
        @DisplayName("getSkillById - 查询不存在的技能应该抛出异常")
        fun `getSkillById should throw BizException when skill not found`() {
            // When & Then
            assertThrows<BizException> {
                skillService.getSkillById(999L)
            }
        }
    }

    @Nested
    @DisplayName("创建技能测试")
    inner class CreateSkillTests {

        @Test
        @DisplayName("createSkill - 创建成功")
        fun `createSkill should create skill successfully`() {
            // Given
            val request = SkillCreateRequest(
                name = "new-skill",
                repositoryId = 1L,
                skillmd = "# 新技能\n这是一个新技能",
                status = 1
            )

            // When
            val result = skillService.createSkill(request)

            // Then
            assertTrue(result)

            // 验证技能可以查询到
            val page = skillService.getSkillPage("new-skill", null, null, 1, 10)
            assertTrue(page.total >= 1)
        }
    }

    @Nested
    @DisplayName("更新技能测试")
    inner class UpdateSkillTests {

        @Test
        @DisplayName("updateSkill - 更新部分字段")
        fun `updateSkill should update partial fields`() {
            // Given
            val request = SkillUpdateRequest(
                name = "web-search-updated",
                skillmd = "# 更新后的技能\n这是更新后的内容"
            )

            // When
            val result = skillService.updateSkill(1L, request)

            // Then
            assertTrue(result)

            // 验证更新成功
            val skill = skillMapper.selectById(1L)
            assertEquals("web-search-updated", skill?.name)
            assertEquals("# 更新后的技能\n这是更新后的内容", skill?.skillmd)
        }

        @Test
        @DisplayName("updateSkill - 更新状态")
        fun `updateSkill should update status`() {
            // Given
            val request = SkillUpdateRequest(
                status = 0
            )

            // When
            val result = skillService.updateSkill(2L, request)

            // Then
            assertTrue(result)

            val skill = skillMapper.selectById(2L)
            assertEquals(0, skill?.status)
        }

        @Test
        @DisplayName("updateSkill - 技能不存在应该抛出异常")
        fun `updateSkill should throw BizException when skill not found`() {
            // Given
            val request = SkillUpdateRequest(
                name = "new-name"
            )

            // When & Then
            assertThrows<BizException> {
                skillService.updateSkill(999L, request)
            }
        }
    }

    @Nested
    @DisplayName("切换技能状态测试")
    inner class ToggleSkillStatusTests {

        @Test
        @DisplayName("toggleSkillStatus - 禁用技能")
        fun `toggleSkillStatus should disable skill`() {
            // When
            val result = skillService.toggleSkillStatus(1L, 0)

            // Then
            assertTrue(result)

            val skill = skillMapper.selectById(1L)
            assertEquals(0, skill?.status)
        }

        @Test
        @DisplayName("toggleSkillStatus - 启用技能")
        fun `toggleSkillStatus should enable skill`() {
            // Given
            skillService.toggleSkillStatus(2L, 0)

            // When
            val result = skillService.toggleSkillStatus(2L, 1)

            // Then
            assertTrue(result)

            val skill = skillMapper.selectById(2L)
            assertEquals(1, skill?.status)
        }

        @Test
        @DisplayName("toggleSkillStatus - 技能不存在应该抛出异常")
        fun `toggleSkillStatus should throw BizException when skill not found`() {
            // When & Then
            assertThrows<BizException> {
                skillService.toggleSkillStatus(999L, 1)
            }
        }
    }

    @Nested
    @DisplayName("删除技能测试")
    inner class DeleteSkillTests {

        @Test
        @DisplayName("deleteSkill - 逻辑删除成功")
        fun `deleteSkill should logically delete skill`() {
            // When
            val result = skillService.deleteSkill(2L)

            // Then
            assertTrue(result)

            val skill = skillMapper.selectById(2L)
            assertEquals(0, skill?.active)
        }

        @Test
        @DisplayName("deleteSkill - 删除不存在的技能应该抛出异常")
        fun `deleteSkill should throw BizException when skill not found`() {
            // When & Then
            assertThrows<BizException> {
                skillService.deleteSkill(999L)
            }
        }
    }

    @Nested
    @DisplayName("根据仓库ID查询技能列表测试")
    inner class GetByRepositoryIdTests {

        @Test
        @DisplayName("getByRepositoryId - 查询仓库的技能列表")
        fun `getByRepositoryId should return skills for repository`() {
            // When
            val skills = skillService.getByRepositoryId(1L)

            // Then
            assertNotNull(skills)
            assertTrue(skills.size >= 2)
            assertTrue(skills.all { it.repositoryId == 1L })
        }

        @Test
        @DisplayName("getByRepositoryId - 查询没有技能的仓库")
        fun `getByRepositoryId should return empty list for repository without skills`() {
            // When
            val skills = skillService.getByRepositoryId(999L)

            // Then
            assertNotNull(skills)
            assertTrue(skills.isEmpty())
        }
    }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：创建 - 查询 - 更新 - 禁用 - 删除")
        fun `complete flow create query update disable delete`() {
            // 1. 创建技能
            val createRequest = SkillCreateRequest(
                name = "flowtest-skill",
                repositoryId = 1L,
                skillmd = "# 流程测试技能\n这是流程测试",
                status = 1
            )
            assertTrue(skillService.createSkill(createRequest))

            // 2. 查询技能
            val page = skillService.getSkillPage("flowtest-skill", null, null, 1, 10)
            assertTrue(page.total >= 1)
            val skillId = page.records[0].id

            // 3. 更新技能
            val updateRequest = SkillUpdateRequest(
                skillmd = "# 更新后的流程测试\n这是更新后的内容",
                status = 1
            )
            assertTrue(skillService.updateSkill(skillId, updateRequest))

            val updatedSkill = skillService.getSkillById(skillId)
            assertEquals("# 更新后的流程测试\n这是更新后的内容", updatedSkill.skillmd)

            // 4. 禁用技能
            assertTrue(skillService.toggleSkillStatus(skillId, 0))
            val disabledSkill = skillMapper.selectById(skillId)
            assertEquals(0, disabledSkill?.status)

            // 5. 删除技能
            assertTrue(skillService.deleteSkill(skillId))
            val deletedSkill = skillMapper.selectById(skillId)
            assertEquals(0, deletedSkill?.active)
        }
    }
}
