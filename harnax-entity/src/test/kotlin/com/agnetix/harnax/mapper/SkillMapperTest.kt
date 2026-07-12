package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Skill
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SkillMapper 集成测试
 *
 * @author agnetix
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class SkillMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
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
    private lateinit var skillMapper: SkillMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询技能")
        fun `selectById should return skill by id`() {
            // When
            val skill = skillMapper.selectById(1L)

            // Then
            assertNotNull(skill)
            assertEquals(1L, skill.id)
            assertEquals("web-search", skill.name)
            assertEquals(1L, skill.repositoryId)
            assertEquals(1, skill.status)
            assertEquals(1, skill.active)
        }

        @Test
        @DisplayName("selectById - 查询不存在的技能返回 null")
        fun `selectById should return null when skill not exists`() {
            // When
            val skill = skillMapper.selectById(999L)

            // Then
            assertNull(skill)
        }

        @Test
        @DisplayName("selectById - 不返回已删除的技能")
        fun `selectById should not return deleted skill`() {
            // When
            val skill = skillMapper.selectById(4L)

            // Then
            assertNull(skill)
        }

        @Test
        @DisplayName("insert - 插入新技能")
        fun `insert should create new skill`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newSkill = Skill().apply {
                name = "new-skill"
                repositoryId = 1L
                description = "新技能"
                skillmd = "# New Skill"
                resources = "{}"
                status = 1
                isPublic = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = skillMapper.insert(newSkill)

            // Then
            assertEquals(1, result)
            assertTrue(newSkill.id > 0)

            val insertedSkill = skillMapper.selectById(newSkill.id)
            assertNotNull(insertedSkill)
            assertEquals("new-skill", insertedSkill.name)
        }

        @Test
        @DisplayName("updateById - 更新技能信息")
        fun `updateById should update skill info`() {
            // Given
            val skillId = 1L
            val skill = skillMapper.selectById(skillId)
            assertNotNull(skill)

            // When
            skill.description = "更新后的描述"
            skill.skillmd = "# Updated Skill"
            skill.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = skillMapper.updateById(skill)

            // Then
            assertEquals(1, result)
            val updatedSkill = skillMapper.selectById(skillId)
            assertNotNull(updatedSkill)
            assertEquals("更新后的描述", updatedSkill.description)
        }

        @Test
        @DisplayName("deleteById - 逻辑删除技能")
        fun `deleteById should logically delete skill`() {
            // Given
            val skillId = 2L
            val skillBefore = skillMapper.selectById(skillId)
            assertNotNull(skillBefore)

            // When
            val result = skillMapper.deleteById(skillId)

            // Then
            assertEquals(1, result)
            val deletedSkill = skillMapper.selectById(skillId)
            assertNull(deletedSkill)
        }
    }

    @Nested
    @DisplayName("状态管理测试")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - 更新技能状态")
        fun `updateStatus should update skill status`() {
            // Given
            val skillId = 1L
            val newStatus = 0

            // When
            val result = skillMapper.updateStatus(skillId, newStatus)
            val updatedSkill = skillMapper.selectById(skillId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedSkill)
            assertEquals(newStatus, updatedSkill.status)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectSkillList - 查询所有技能列表")
        fun `selectSkillList should return all skills`() {
            // When
            val skills = skillMapper.selectSkillList(null, null, null, "admin")

            // Then
            assertTrue(skills.isNotEmpty())
            assertTrue(skills.size >= 3)
        }

        @Test
        @DisplayName("selectSkillList - 按仓库 ID 查询")
        fun `selectSkillList should filter by repository id`() {
            // When
            val skills = skillMapper.selectSkillList(null, 1L, null, "admin")

            // Then
            assertTrue(skills.isNotEmpty())
            skills.forEach {
                assertEquals(1L, it.repositoryId)
            }
        }

        @Test
        @DisplayName("selectByNameAndRepo - 根据名称和仓库查询技能")
        fun `selectByNameAndRepo should return skill by name and repository`() {
            // When
            val skill = skillMapper.selectByNameAndRepo("web-search", 1L)

            // Then
            assertNotNull(skill)
            assertEquals("web-search", skill.name)
            assertEquals(1L, skill.repositoryId)
        }
    }

    @Nested
    @DisplayName("租户隔离测试")
    inner class TenantIsolationTests {

        @Test
        @DisplayName("selectSkillList - 按租户 ID 过滤")
        fun `selectSkillList should filter by tenantId`() {
            // When: tenant 1 admin
            val tenant1Skills = skillMapper.selectSkillList(null, null, null, "admin", 1L)

            // Then: should only see tenant 1 skills
            assertTrue(tenant1Skills.isNotEmpty())
            assertTrue(tenant1Skills.all { it.tenantId == 1L })
            assertEquals(3, tenant1Skills.size) // web-search, code-review, data-analysis
        }

        @Test
        @DisplayName("selectSkillList - 不同租户数据隔离")
        fun `selectSkillList should isolate between tenants`() {
            // When
            val tenant1Skills = skillMapper.selectSkillList(null, null, null, "admin", 1L)
            val tenant2Skills = skillMapper.selectSkillList(null, null, null, "user2", 2L)

            // Then
            assertTrue(tenant1Skills.isNotEmpty())
            assertTrue(tenant2Skills.isNotEmpty())
            assertTrue(tenant1Skills.none { it.name == "tenant2-skill" })
            assertTrue(tenant2Skills.none { it.name == "web-search" })
        }

        @Test
        @DisplayName("selectSkillList - 不传 tenantId 返回所有租户数据")
        fun `selectSkillList without tenantId returns all tenants`() {
            // When
            val allSkills = skillMapper.selectSkillList(null, null, null, "admin", null)

            // Then: should include skills from both tenants (admin sees public + own)
            assertTrue(allSkills.size >= 4) // 3 tenant1 + at least 1 tenant2
        }

        @Test
        @DisplayName("insert - 带 tenantId 插入并验证隔离")
        fun `insert with tenantId should persist and isolate`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newSkill = Skill().apply {
                tenantId = 2L
                name = "tenant2-new-skill"
                repositoryId = 4L
                description = "租户2新技能"
                skillmd = "# New"
                resources = "{}"
                status = 1
                isPublic = 1
                creator = "user2"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            skillMapper.insert(newSkill)

            // Then: tenant 2 should see it
            val tenant2Skills = skillMapper.selectSkillList(null, null, null, "user2", 2L)
            assertTrue(tenant2Skills.any { it.name == "tenant2-new-skill" })

            // Tenant 1 should NOT see it
            val tenant1Skills = skillMapper.selectSkillList(null, null, null, "admin", 1L)
            assertTrue(tenant1Skills.none { it.name == "tenant2-new-skill" })
        }

        @Test
        @DisplayName("selectById - 返回结果的 tenantId 正确")
        fun `selectById should return correct tenantId`() {
            // When
            val skill = skillMapper.selectById(1L)

            // Then
            assertNotNull(skill)
            assertEquals(1L, skill.tenantId)
        }
    }
}
