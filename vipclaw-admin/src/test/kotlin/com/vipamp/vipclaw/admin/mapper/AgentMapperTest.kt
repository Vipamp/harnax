package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Agent
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
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AgentMapper 集成测试
 *
 * @author vipamp
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AgentMapperTest {

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
    private lateinit var agentMapper: AgentMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询智能体")
        fun `selectById should return agent by id`() {
            // When
            val agent = agentMapper.selectById(1L)

            // Then
            assertNotNull(agent)
            assertEquals(1L, agent.id)
            assertEquals("Test Agent 1", agent.name)
            assertEquals("测试智能体1", agent.description)
            assertEquals("你是一个助手", agent.systemPrompt)
            assertEquals(1L, agent.modelId)
            assertEquals(1, agent.status)
            assertEquals(1, agent.isPublic)
            assertEquals("testuser1", agent.owner)
            assertEquals(1, agent.active)
        }

        @Test
        @DisplayName("selectById - 查询不存在的智能体返回 null")
        fun `selectById should return null when agent not exists`() {
            // When
            val agent = agentMapper.selectById(999L)

            // Then
            assertNull(agent)
        }

        @Test
        @DisplayName("selectById - 不返回已删除的智能体")
        fun `selectById should not return deleted agent`() {
            // When
            val agent = agentMapper.selectById(4L)

            // Then
            assertNull(agent)
        }

        @Test
        @DisplayName("insert - 插入新智能体")
        fun `insert should create new agent`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newAgent = Agent().apply {
                name = "New Agent"
                description = "新智能体"
                systemPrompt = "你是一个新助手"
                modelId = 1L
                mcpList = "[]"
                skillList = "[1]"
                owner = "testuser1"
                status = 1
                isPublic = 1
                creator = "testuser1"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = agentMapper.insert(newAgent)

            // Then
            assertEquals(1, result)
            assertTrue(newAgent.id > 0)

            val insertedAgent = agentMapper.selectById(newAgent.id)
            assertNotNull(insertedAgent)
            assertEquals("New Agent", insertedAgent.name)
        }

        @Test
        @DisplayName("updateById - 更新智能体信息")
        fun `updateById should update agent info`() {
            // Given
            val agentId = 1L
            val agent = agentMapper.selectById(agentId)
            assertNotNull(agent)

            // When
            agent.name = "Updated Agent"
            agent.description = "更新后的描述"
            agent.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = agentMapper.updateById(agent)

            // Then
            assertEquals(1, result)
            val updatedAgent = agentMapper.selectById(agentId)
            assertNotNull(updatedAgent)
            assertEquals("Updated Agent", updatedAgent.name)
            assertEquals("更新后的描述", updatedAgent.description)
        }

        @Test
        @DisplayName("deleteById - 逻辑删除智能体")
        fun `deleteById should logically delete agent`() {
            // Given
            val agentId = 2L
            val agentBefore = agentMapper.selectById(agentId)
            assertNotNull(agentBefore)

            // When
            val result = agentMapper.deleteById(agentId)

            // Then
            assertEquals(1, result)
            val deletedAgent = agentMapper.selectById(agentId)
            assertNull(deletedAgent)
        }
    }

    @Nested
    @DisplayName("状态管理测试")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - 更新智能体状态")
        fun `updateStatus should update agent status`() {
            // Given
            val agentId = 1L
            val newStatus = 0

            // When
            val result = agentMapper.updateStatus(agentId, newStatus)
            val updatedAgent = agentMapper.selectById(agentId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedAgent)
            assertEquals(newStatus, updatedAgent.status)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectAgentList - 查询所有智能体列表")
        fun `selectAgentList should return all agents`() {
            // When
            val agents = agentMapper.selectAgentList(null, null, "testuser1")

            // Then
            assertTrue(agents.isNotEmpty())
            assertTrue(agents.size >= 3) // 至少有 3 个 active=1 的智能体
        }

        @Test
        @DisplayName("selectAgentList - 按名称模糊查询")
        fun `selectAgentList should filter by name`() {
            // When
            val agents = agentMapper.selectAgentList("Test Agent", null, "testuser1")

            // Then
            assertTrue(agents.isNotEmpty())
            agents.forEach {
                assertTrue(it.name.contains("Test Agent"))
            }
        }

        @Test
        @DisplayName("selectAgentList - 权限过滤：返回公开或当前用户创建的")
        fun `selectAgentList should filter by permission`() {
            // When - testuser1 应该看到自己创建的和公开的
            val agents = agentMapper.selectAgentList(null, null, "testuser1")

            // Then
            assertTrue(agents.isNotEmpty())
            agents.forEach {
                assertTrue(it.isPublic == 1 || it.creator == "testuser1")
            }
        }
    }
}
