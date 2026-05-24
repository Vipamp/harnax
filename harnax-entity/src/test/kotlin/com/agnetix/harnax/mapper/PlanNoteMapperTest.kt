package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.PlanNoteEntity
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PlanNoteMapperTest {

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
    private lateinit var planNoteMapper: PlanNoteMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询计划")
        fun `selectById should return plan note by id`() {
            val planNote = planNoteMapper.selectById(1L)
            assertNotNull(planNote)
            assertEquals(1L, planNote.id)
            assertEquals("session-001", planNote.sessionId)
            assertEquals("plan-001", planNote.planId)
            assertEquals("数据分析计划", planNote.name)
            assertEquals("IN_PROGRESS", planNote.status)
        }

        @Test
        @DisplayName("selectById - 查询不存在的计划返回 null")
        fun `selectById should return null when plan note not exists`() {
            val planNote = planNoteMapper.selectById(999L)
            assertNull(planNote)
        }

        @Test
        @DisplayName("insert - 插入新计划")
        fun `insert should create new plan note`() {
            val newPlanNote = PlanNoteEntity().apply {
                sessionId = "session-001"
                planId = "plan-new"
                name = "新计划"
                description = "测试新计划"
                expectedOutcome = "预期结果"
                subtasks = "[]"
                status = "TODO"
                costTimeseconds = 0L
                createdAt = "2026-04-28T10:00:00"
            }

            val result = planNoteMapper.insert(newPlanNote)
            assertEquals(1, result)
            assertTrue(newPlanNote.id > 0)

            val insertedPlanNote = planNoteMapper.selectById(newPlanNote.id)
            assertNotNull(insertedPlanNote)
            assertEquals("新计划", insertedPlanNote.name)
        }

        @Test
        @DisplayName("updateById - 更新计划信息")
        fun `updateById should update plan note info`() {
            val planNote = planNoteMapper.selectById(1L)
            assertNotNull(planNote)

            planNote.name = "更新后的计划"
            planNote.status = "DONE"
            val result = planNoteMapper.updateById(planNote)

            assertEquals(1, result)
            val updatedPlanNote = planNoteMapper.selectById(1L)
            assertNotNull(updatedPlanNote)
            assertEquals("更新后的计划", updatedPlanNote.name)
            assertEquals("DONE", updatedPlanNote.status)
        }

        @Test
        @DisplayName("deleteById - 删除计划")
        fun `deleteById should delete plan note`() {
            val result = planNoteMapper.deleteById(1L)
            assertEquals(1, result)
            val deletedPlanNote = planNoteMapper.selectById(1L)
            assertNull(deletedPlanNote)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectBySessionId - 根据会话 ID 查询计划列表")
        fun `selectBySessionId should return plan notes by session id`() {
            val planNotes = planNoteMapper.selectBySessionId("session-001")
            assertTrue(planNotes.isNotEmpty())
            assertTrue(planNotes.size >= 2)
            planNotes.forEach {
                assertEquals("session-001", it.sessionId)
            }
        }
    }
}
