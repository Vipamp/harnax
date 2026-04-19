package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.McpServerCreateRequest
import com.vipamp.vipclaw.admin.dto.McpServerUpdateRequest
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.McpServerMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * McpServerServiceImpl 集成测试
 * 使用 Testcontainers 启动真实的 MySQL 容器进行测试
 *
 * @author vipamp
 * @since 2026-04-20
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class McpServerServiceImplIntegrationTest {

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
    private lateinit var mcpServerService: McpServerServiceImpl

    @Autowired
    private lateinit var mcpServerMapper: McpServerMapper

    @Nested
    @DisplayName("分页查询测试")
    inner class PaginationTests {

        @Test
        @DisplayName("getMcpServerPage - 正常分页查询")
        fun `getMcpServerPage should return paginated results`() {
            // When
            val page = mcpServerService.getMcpServerPage(null, null, null, 1, 2)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 2) // schema-test.sql 中有3条，但deleted的active=0
            assertEquals(2, page.size)
            assertEquals(1, page.current)
            assertEquals(2, page.records.size)
        }

        @Test
        @DisplayName("getMcpServerPage - 关键字搜索")
        fun `getMcpServerPage should filter by keyword`() {
            // When
            val page = mcpServerService.getMcpServerPage("Weather", null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.name.contains("Weather") || it.description?.contains("Weather") == true })
        }

        @Test
        @DisplayName("getMcpServerPage - 状态过滤")
        fun `getMcpServerPage should filter by status`() {
            // When
            val page = mcpServerService.getMcpServerPage(null, 1, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 2)
            assertTrue(page.records.all { it.status == 1 })
        }
    }

    @Nested
    @DisplayName("查询MCP服务详情测试")
    inner class GetMcpServerByIdTests {

        @Test
        @DisplayName("getMcpServerById - 查询存在的MCP服务")
        fun `getMcpServerById should return mcp server when exists`() {
            // When
            val mcpServer = mcpServerService.getMcpServerById(1L)

            // Then
            assertNotNull(mcpServer)
            assertEquals(1L, mcpServer.id)
            assertEquals("Weather MCP", mcpServer.name)
        }

        @Test
        @DisplayName("getMcpServerById - 查询不存在的MCP服务应该抛出异常")
        fun `getMcpServerById should throw BizException when mcp server not found`() {
            // When & Then
            assertThrows<BizException> {
                mcpServerService.getMcpServerById(999L)
            }
        }
    }

    @Nested
    @DisplayName("创建MCP服务测试")
    inner class CreateMcpServerTests {

        @Test
        @DisplayName("createMcpServer - 创建成功")
        fun `createMcpServer should create mcp server successfully`() {
            // Given
            val request = McpServerCreateRequest(
                name = "New MCP Server",
                description = "新MCP服务",
                url = "http://localhost:8084/new",
                status = 1
            )

            // When
            val result = mcpServerService.createMcpServer(request)

            // Then
            assertTrue(result)

            // 验证MCP服务可以查询到
            val page = mcpServerService.getMcpServerPage("New MCP Server", null, null, 1, 10)
            assertTrue(page.total >= 1)
        }
    }

    @Nested
    @DisplayName("更新MCP服务测试")
    inner class UpdateMcpServerTests {

        @Test
        @DisplayName("updateMcpServer - 更新部分字段")
        fun `updateMcpServer should update partial fields`() {
            // Given
            val request = McpServerUpdateRequest(
                name = "Updated Weather MCP",
                description = "更新后的描述"
            )

            // When
            val result = mcpServerService.updateMcpServer(1L, request)

            // Then
            assertTrue(result)

            // 验证更新成功
            val mcpServer = mcpServerMapper.selectById(1L)
            assertEquals("Updated Weather MCP", mcpServer?.name)
            assertEquals("更新后的描述", mcpServer?.description)
        }

        @Test
        @DisplayName("updateMcpServer - 更新状态")
        fun `updateMcpServer should update status`() {
            // Given
            val request = McpServerUpdateRequest(
                status = 0
            )

            // When
            val result = mcpServerService.updateMcpServer(2L, request)

            // Then
            assertTrue(result)

            val mcpServer = mcpServerMapper.selectById(2L)
            assertEquals(0, mcpServer?.status)
        }

        @Test
        @DisplayName("updateMcpServer - MCP服务不存在应该抛出异常")
        fun `updateMcpServer should throw BizException when mcp server not found`() {
            // Given
            val request = McpServerUpdateRequest(
                name = "New Name"
            )

            // When & Then
            assertThrows<RuntimeException> {
                mcpServerService.updateMcpServer(999L, request)
            }
        }
    }

    @Nested
    @DisplayName("切换MCP服务状态测试")
    inner class ToggleMcpServerStatusTests {

        @Test
        @DisplayName("toggleMcpServerStatus - 禁用MCP服务")
        fun `toggleMcpServerStatus should disable mcp server`() {
            // When
            val result = mcpServerService.toggleMcpServerStatus(1L, 0)

            // Then
            assertTrue(result)

            // 验证状态已更新
            val mcpServer = mcpServerMapper.selectById(1L)
            assertEquals(0, mcpServer?.status)
        }

        @Test
        @DisplayName("toggleMcpServerStatus - 启用MCP服务")
        fun `toggleMcpServerStatus should enable mcp server`() {
            // Given - 先禁用
            mcpServerService.toggleMcpServerStatus(2L, 0)

            // When - 再启用
            val result = mcpServerService.toggleMcpServerStatus(2L, 1)

            // Then
            assertTrue(result)

            val mcpServer = mcpServerMapper.selectById(2L)
            assertEquals(1, mcpServer?.status)
        }

        @Test
        @DisplayName("toggleMcpServerStatus - MCP服务不存在应该抛出异常")
        fun `toggleMcpServerStatus should throw BizException when mcp server not found`() {
            // When & Then
            assertThrows<BizException> {
                mcpServerService.toggleMcpServerStatus(999L, 1)
            }
        }
    }

    @Nested
    @DisplayName("删除MCP服务测试")
    inner class DeleteMcpServerTests {

        @Test
        @DisplayName("deleteMcpServer - 逻辑删除成功")
        fun `deleteMcpServer should logically delete mcp server`() {
            // When
            val result = mcpServerService.deleteMcpServer(2L)

            // Then
            assertTrue(result)

            // 验证 active 已变为 0
            val mcpServer = mcpServerMapper.selectById(2L)
            assertEquals(0, mcpServer?.active)
        }

        @Test
        @DisplayName("deleteMcpServer - 删除不存在的MCP服务应该抛出异常")
        fun `deleteMcpServer should throw BizException when mcp server not found`() {
            // When & Then
            assertThrows<BizException> {
                mcpServerService.deleteMcpServer(999L)
            }
        }
    }

    @Nested
    @DisplayName("连通性测试")
    inner class ConnectivityTestTests {

        @Test
        @DisplayName("connectivityTest - 测试存在的MCP服务")
        fun `connectivityTest should test existing mcp server`() {
            // When - 由于是测试环境，URL不可达，应该返回false
            val result = mcpServerService.connectivityTest(1L)

            // Then
            assertFalse(result) // URL不可达
        }

        @Test
        @DisplayName("connectivityTest - 测试不存在的MCP服务应该抛出异常")
        fun `connectivityTest should throw BizException when mcp server not found`() {
            // When & Then
            assertThrows<BizException> {
                mcpServerService.connectivityTest(999L)
            }
        }
    }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：创建 - 查询 - 更新 - 禁用 - 删除")
        fun `complete flow create query update disable delete`() {
            // 1. 创建MCP服务
            val createRequest = McpServerCreateRequest(
                name = "FlowTest MCP",
                description = "流程测试MCP服务",
                url = "http://localhost:8085/flowtest",
                status = 1
            )
            assertTrue(mcpServerService.createMcpServer(createRequest))

            // 2. 查询MCP服务
            val page = mcpServerService.getMcpServerPage("FlowTest MCP", null, null, 1, 10)
            assertTrue(page.total >= 1)
            val mcpId = page.records[0].id

            // 3. 更新MCP服务
            val updateRequest = McpServerUpdateRequest(
                description = "更新后的流程测试",
                status = 1
            )
            assertTrue(mcpServerService.updateMcpServer(mcpId, updateRequest))

            val updatedMcp = mcpServerService.getMcpServerById(mcpId)
            assertEquals("更新后的流程测试", updatedMcp.description)

            // 4. 禁用MCP服务
            assertTrue(mcpServerService.toggleMcpServerStatus(mcpId, 0))
            val disabledMcp = mcpServerMapper.selectById(mcpId)
            assertEquals(0, disabledMcp?.status)

            // 5. 删除MCP服务
            assertTrue(mcpServerService.deleteMcpServer(mcpId))
            val deletedMcp = mcpServerMapper.selectById(mcpId)
            assertEquals(0, deletedMcp?.active)
        }
    }
}
