package com.agnetix.harnax.admin.mapper

import com.agnetix.harnax.admin.entity.McpServer
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
 * McpServerMapper Integration Tests
 *
 * @author agnetix
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class McpServerMapperTest {

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
    private lateinit var mcpServerMapper: McpServerMapper

    @Nested
    @DisplayName("Basic CRUD Tests")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - Query MCP server by ID")
        fun `selectById should return mcp server by id`() {
            // When
            val mcpServer = mcpServerMapper.selectById(1L)

            // Then
            assertNotNull(mcpServer)
            assertEquals(1L, mcpServer.id)
            assertEquals("Weather MCP", mcpServer.name)
            assertEquals("天气查询服务", mcpServer.description)
            assertEquals("streamablehttp", mcpServer.type)
            assertEquals("http://localhost:8081/weather", mcpServer.url)
            assertEquals(1, mcpServer.status)
            assertEquals(1, mcpServer.active)
        }

        @Test
        @DisplayName("selectById - Return null when MCP server not exists")
        fun `selectById should return null when mcp server not exists`() {
            // When
            val mcpServer = mcpServerMapper.selectById(999L)

            // Then
            assertNull(mcpServer)
        }

        @Test
        @DisplayName("selectById - Do not return deleted MCP server")
        fun `selectById should not return deleted mcp server`() {
            // When
            val mcpServer = mcpServerMapper.selectById(4L)

            // Then
            assertNull(mcpServer)
        }

        @Test
        @DisplayName("insert - Insert new MCP server")
        fun `insert should create new mcp server`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newMcpServer = McpServer().apply {
                name = "New MCP"
                description = "新MCP服务"
                type = "stdio"
                command = "python app.py"
                url = ""
                status = 1
                isPublic = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = mcpServerMapper.insert(newMcpServer)

            // Then
            assertEquals(1, result)
            assertTrue(newMcpServer.id > 0)

            val insertedMcpServer = mcpServerMapper.selectById(newMcpServer.id)
            assertNotNull(insertedMcpServer)
            assertEquals("New MCP", insertedMcpServer.name)
        }

        @Test
        @DisplayName("updateById - Update MCP server info")
        fun `updateById should update mcp server info`() {
            // Given
            val mcpServerId = 1L
            val mcpServer = mcpServerMapper.selectById(mcpServerId)
            assertNotNull(mcpServer)

            // When
            mcpServer.name = "Updated MCP"
            mcpServer.description = "更新后的描述"
            mcpServer.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = mcpServerMapper.updateById(mcpServer)

            // Then
            assertEquals(1, result)
            val updatedMcpServer = mcpServerMapper.selectById(mcpServerId)
            assertNotNull(updatedMcpServer)
            assertEquals("Updated MCP", updatedMcpServer.name)
        }

        @Test
        @DisplayName("deleteById - Logically delete MCP server")
        fun `deleteById should logically delete mcp server`() {
            // Given
            val mcpServerId = 2L
            val mcpServerBefore = mcpServerMapper.selectById(mcpServerId)
            assertNotNull(mcpServerBefore)

            // When
            val result = mcpServerMapper.deleteById(mcpServerId)

            // Then
            assertEquals(1, result)
            val deletedMcpServer = mcpServerMapper.selectById(mcpServerId)
            assertNull(deletedMcpServer)
        }
    }

    @Nested
    @DisplayName("Status Management Tests")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - Update MCP server status")
        fun `updateStatus should update mcp server status`() {
            // Given
            val mcpServerId = 1L
            val newStatus = 0

            // When
            val result = mcpServerMapper.updateStatus(mcpServerId, newStatus)
            val updatedMcpServer = mcpServerMapper.selectById(mcpServerId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedMcpServer)
            assertEquals(newStatus, updatedMcpServer.status)
        }
    }

    @Nested
    @DisplayName("Custom Query Tests")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectMcpServerList - Query all MCP servers")
        fun `selectMcpServerList should return all mcp servers`() {
            // When
            val mcpServers = mcpServerMapper.selectMcpServerList(null, null, null, "admin")

            // Then
            assertTrue(mcpServers.isNotEmpty())
            assertTrue(mcpServers.size >= 3)
        }

        @Test
        @DisplayName("selectMcpServerList - Filter by name")
        fun `selectMcpServerList should filter by name`() {
            // When
            val mcpServers = mcpServerMapper.selectMcpServerList("Weather", null, null, "admin")

            // Then
            assertTrue(mcpServers.isNotEmpty())
            mcpServers.forEach {
                assertTrue(it.name.contains("Weather"))
            }
        }

        @Test
        @DisplayName("selectMcpServerList - Filter by status")
        fun `selectMcpServerList should filter by status`() {
            // When
            val mcpServers = mcpServerMapper.selectMcpServerList(null, 0, null, "admin")

            // Then
            // 可能为空，因为测试数据中所有 MCP 服务状态都是 1
            mcpServers.forEach {
                assertEquals(0, it.status)
            }
        }

        @Test
        @DisplayName("selectByName - Query MCP server by name")
        fun `selectByName should return mcp server by name`() {
            // When
            val mcpServer = mcpServerMapper.selectByName("Weather MCP")

            // Then
            assertNotNull(mcpServer)
            assertEquals("Weather MCP", mcpServer.name)
        }

        @Test
        @DisplayName("selectByName - Return null when name not exists")
        fun `selectByName should return null when name not exists`() {
            // When
            val mcpServer = mcpServerMapper.selectByName("Nonexistent MCP")

            // Then
            assertNull(mcpServer)
        }
    }
}
