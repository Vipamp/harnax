package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.AgentCreateRequest
import com.vipamp.vipclaw.admin.dto.AgentUpdateRequest
import com.vipamp.vipclaw.admin.mapper.AgentMapper
import org.junit.jupiter.api.*
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * AgentServiceImpl 集成测试
 * 使用 Testcontainers 启动真实的 MySQL 容器进行测试
 *
 * @author vipamp
 * @since 2026-04-20
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AgentServiceImplIntegrationTest {

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
    private lateinit var agentService: AgentServiceImpl

    @Autowired
    private lateinit var agentMapper: AgentMapper

    @Nested
    @DisplayName("分页查询测试")
    inner class PaginationTests {

        @Test
        @DisplayName("getAgentPage - 正常分页查询")
        fun `getAgentPage should return paginated results`() {
            // When
            val page = agentService.getAgentPage(null, null, 1, 2)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 3) // schema-test.sql 中有4条，但deleted的active=0
            assertEquals(2, page.size)
            assertEquals(1, page.current)
            assertEquals(2, page.records.size)
        }

        @Test
        @DisplayName("getAgentPage - 名称搜索")
        fun `getAgentPage should filter by name`() {
            // When
            val page = agentService.getAgentPage("Test Agent 1", null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.name.contains("Test Agent 1") })
        }

        @Test
        @DisplayName("getAgentPage - 状态过滤")
        fun `getAgentPage should filter by status`() {
            // When
            val page = agentService.getAgentPage(null, 0, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.status == 0 })
        }
    }

    @Nested
    @DisplayName("查询智能体详情测试")
    inner class GetAgentByIdTests {

        @Test
        @DisplayName("getAgentById - 查询存在的智能体")
        fun `getAgentById should return agent when exists`() {
            // When
            val agent = agentService.getAgentById(1L)

            // Then
            assertNotNull(agent)
            assertEquals(1L, agent?.id)
            assertEquals("Test Agent 1", agent?.name)
        }

        @Test
        @DisplayName("getAgentById - 查询不存在的智能体返回null")
        fun `getAgentById should return null when agent not found`() {
            // When
            val agent = agentService.getAgentById(999L)

            // Then
            assertNull(agent)
        }
    }

    @Nested
    @DisplayName("创建智能体测试")
    inner class CreateAgentTests {

        @Test
        @DisplayName("createAgent - 创建成功")
        fun `createAgent should create agent successfully`() {
            // Given
            val request = AgentCreateRequest(
                name = "New Agent",
                description = "新智能体",
                systemPrompt = "你是一个助手",
                modelId = 1L,
                mcpList = listOf(
                    AgentCreateRequest.McpConfig(id = 1L, enableSkip = "false")
                ),
                skillList = "1,2",
                owner = "testuser1",
                status = 1
            )

            // When
            val result = agentService.createAgent(request)

            // Then
            assertTrue(result)

            // 验证智能体可以查询到
            val agent = agentMapper.selectAgentList("New Agent", null, "testuser1")
            assertTrue(agent.isNotEmpty())
            assertEquals("新智能体", agent[0].description)
        }

        @Test
        @DisplayName("createAgent - 创建最小化智能体")
        fun `createAgent should create minimal agent`() {
            // Given
            val request = AgentCreateRequest(
                name = "Minimal Agent",
                description = "最小化智能体",
                systemPrompt = "简单助手",
                modelId = 1L,
                owner = "testuser1"
            )

            // When
            val result = agentService.createAgent(request)

            // Then
            assertTrue(result)
            val agent = agentMapper.selectAgentList("Minimal Agent", null, "testuser1")
            assertTrue(agent.isNotEmpty())
        }
    }

    @Nested
    @DisplayName("更新智能体测试")
    inner class UpdateAgentTests {

        @Test
        @DisplayName("updateAgent - 更新部分字段")
        fun `updateAgent should update partial fields`() {
            // Given
            val request = AgentUpdateRequest(
                name = "Updated Agent 1",
                description = "更新后的描述"
            )

            // When
            val result = agentService.updateAgent(1L, request)

            // Then
            assertTrue(result)

            // 验证更新成功
            val agent = agentMapper.selectById(1L)
            assertEquals("Updated Agent 1", agent?.name)
            assertEquals("更新后的描述", agent?.description)
        }

        @Test
        @DisplayName("updateAgent - 更新状态和公开属性")
        fun `updateAgent should update status and isPublic`() {
            // Given
            val request = AgentUpdateRequest(
                status = 0,
                isPublic = 0
            )

            // When
            val result = agentService.updateAgent(2L, request)

            // Then
            assertTrue(result)

            val agent = agentMapper.selectById(2L)
            assertEquals(0, agent?.status)
            assertEquals(0, agent?.isPublic)
        }

        @Test
        @DisplayName("updateAgent - 智能体不存在应该抛出异常")
        fun `updateAgent should throw exception when agent not found`() {
            // Given
            val request = AgentUpdateRequest(
                name = "New Name"
            )

            // When & Then
            assertThrows<RuntimeException> {
                agentService.updateAgent(999L, request)
            }
        }
    }

    @Nested
    @DisplayName("切换智能体状态测试")
    inner class ToggleAgentStatusTests {

        @Test
        @DisplayName("toggleAgentStatus - 禁用智能体")
        fun `toggleAgentStatus should disable agent`() {
            // When
            val result = agentService.toggleAgentStatus(1L, 0)

            // Then
            assertTrue(result)

            // 验证状态已更新
            val agent = agentMapper.selectById(1L)
            assertEquals(0, agent?.status)
        }

        @Test
        @DisplayName("toggleAgentStatus - 启用智能体")
        fun `toggleAgentStatus should enable agent`() {
            // Given - 先禁用
            agentService.toggleAgentStatus(3L, 0)

            // When - 再启用
            val result = agentService.toggleAgentStatus(3L, 1)

            // Then
            assertTrue(result)

            val agent = agentMapper.selectById(3L)
            assertEquals(1, agent?.status)
        }

        @Test
        @DisplayName("toggleAgentStatus - 智能体不存在应该抛出异常")
        fun `toggleAgentStatus should throw exception when agent not found`() {
            // When & Then
            assertThrows<RuntimeException> {
                agentService.toggleAgentStatus(999L, 1)
            }
        }
    }

    @Nested
    @DisplayName("删除智能体测试")
    inner class DeleteAgentTests {

        @Test
        @DisplayName("deleteAgent - 逻辑删除成功")
        fun `deleteAgent should logically delete agent`() {
            // When
            val result = agentService.deleteAgent(2L)

            // Then
            assertTrue(result)

            // 验证 active 已变为 0
            val agent = agentMapper.selectById(2L)
            assertEquals(0, agent?.active)
        }

        @Test
        @DisplayName("deleteAgent - 删除不存在的智能体")
        fun `deleteAgent should return false when agent not found`() {
            // When
            val result = agentService.deleteAgent(999L)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("转换为响应DTO测试")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - 转换成功")
        fun `convertToResponse should convert successfully`() {
            // When
            val response = agentService.convertToResponse(agentMapper.selectById(1L))

            // Then
            assertNotNull(response)
            assertEquals(1L, response?.id)
            assertEquals("Test Agent 1", response?.name)
            assertNotNull(response?.sessionList)
        }

        @Test
        @DisplayName("convertToResponse - null输入返回null")
        fun `convertToResponse should return null for null input`() {
            // When
            val response = agentService.convertToResponse(null)

            // Then
            assertNull(response)
        }
    }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：创建 - 查询 - 更新 - 禁用 - 删除")
        fun `complete flow create query update disable delete`() {
            // 1. 创建智能体
            val createRequest = AgentCreateRequest(
                name = "FlowTest Agent",
                description = "流程测试智能体",
                systemPrompt = "你是一个测试助手",
                modelId = 1L,
                owner = "testuser1",
                status = 1
            )
            assertTrue(agentService.createAgent(createRequest))

            // 2. 查询智能体
            val agents = agentMapper.selectAgentList("FlowTest Agent", null, "testuser1")
            assertTrue(agents.isNotEmpty())
            val agentId = agents[0].id

            // 3. 更新智能体
            val updateRequest = AgentUpdateRequest(
                description = "更新后的流程测试",
                status = 1
            )
            assertTrue(agentService.updateAgent(agentId, updateRequest))

            val updatedAgent = agentService.getAgentById(agentId)
            assertEquals("更新后的流程测试", updatedAgent?.description)

            // 4. 禁用智能体
            assertTrue(agentService.toggleAgentStatus(agentId, 0))
            val disabledAgent = agentMapper.selectById(agentId)
            assertEquals(0, disabledAgent?.status)

            // 5. 删除智能体
            assertTrue(agentService.deleteAgent(agentId))
            val deletedAgent = agentMapper.selectById(agentId)
            assertEquals(0, deletedAgent?.active)
        }
    }
}
