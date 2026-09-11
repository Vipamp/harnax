package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.McpServer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DuplicateKeyException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
open class McpServerMapperTest {

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

    private fun insertServer(name: String, tenantId: Long): McpServer {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        return McpServer().apply {
            this.name = name
            description = "批量与租户查询用"
            type = "streamablehttp"
            url = "http://localhost:1/mcp"
            status = 1
            isPublic = 1
            creator = "admin"
            active = 1
            this.tenantId = tenantId
            createTime = now
            updateTime = now
        }.also { mcpServerMapper.insert(it) }
    }

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
        @DisplayName("insert - Insert MCP server with headers and envs")
        fun `insert should create mcp server with headers and envs`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val headersJson = """[{"key":"Authorization","value":"Bearer sk-test","secret":true}]"""
            val envsJson = """[{"key":"API_KEY","value":"sk-test-key","secret":true},{"key":"DEBUG","value":"false","secret":false}]"""
            val newMcpServer = McpServer().apply {
                name = "MCP With Config"
                description = "MCP with headers and envs"
                type = "streamablehttp"
                url = "http://localhost:8080/mcp"
                status = 1
                isPublic = 1
                creator = "admin"
                active = 1
                headers = headersJson
                envParams = envsJson
                createTime = now
                updateTime = now
            }

            // When
            val result = mcpServerMapper.insert(newMcpServer)

            // Then
            assertEquals(1, result)
            assertTrue(newMcpServer.id > 0)

            val inserted = mcpServerMapper.selectById(newMcpServer.id)
            assertNotNull(inserted)
            assertEquals("MCP With Config", inserted.name)
            assertEquals(headersJson, inserted.headers)
            assertEquals(envsJson, inserted.envParams)
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
        @DisplayName("updateOAuthConfig - Only the oauth_config column moves")
        fun `updateOAuthConfig should touch nothing but the oauth config`() {
            // Given - 发现成功后要把 issuer 写回这一列，但那一行别的东西正被编辑页拥有
            val server = insertServer("Issuer Writeback MCP", 77L)

            // When
            val result = mcpServerMapper.updateOAuthConfig(server.id, """{"authorizationServer":"https://as.example.com"}""")

            // Then - updateById 的 SET 是无条件的，整行回写会覆盖别人的编辑，所以这里要逐列确认没动
            assertEquals(1, result)
            val updated = mcpServerMapper.selectById(server.id)
            assertNotNull(updated)
            assertEquals("""{"authorizationServer":"https://as.example.com"}""", updated.oauthConfig)
            assertEquals("Issuer Writeback MCP", updated.name)
            assertEquals("http://localhost:1/mcp", updated.url)
            assertEquals(1, updated.status)
            assertEquals(77L, updated.tenantId)
        }

        @Test
        @DisplayName("updateOAuthConfig - A logically deleted row is not revived")
        fun `updateOAuthConfig should skip a deleted row`() {
            val server = insertServer("Deleted Writeback MCP", 78L)
            mcpServerMapper.deleteById(server.id)

            val result = mcpServerMapper.updateOAuthConfig(server.id, """{"authorizationServer":"https://as.example.com"}""")

            assertEquals(0, result)
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

        @Test
        @DisplayName("selectByName - Tenant id limits the lookup")
        fun `selectByName should limit the lookup to the given tenant`() {
            // Given - 唯一键按租户算，重名检查也必须是同口径，否则别租户占了名字自己就建不了
            insertServer("Cross Tenant Name MCP", 33L)

            // When & Then
            assertNotNull(mcpServerMapper.selectByName("Cross Tenant Name MCP", 33L))
            assertNull(mcpServerMapper.selectByName("Cross Tenant Name MCP", 44L))
        }

        @Test
        @DisplayName("uk_mcp_server_tenant_active_name - Guards active names per tenant")
        fun `unique key should guard active names per tenant`() {
            // Given - V23 的生成列让已删除行退出唯一键，互斥只发生在同租户的有效行之间
            val first = insertServer("Guard MCP", 55L)

            // When & Then
            assertThrows<DuplicateKeyException> { insertServer("Guard MCP", 55L) }
            assertNotNull(insertServer("Guard MCP", 66L))

            mcpServerMapper.deleteById(first.id)
            assertNotNull(insertServer("Guard MCP", 55L))
        }

        @Test
        @DisplayName("selectByIds - Batch fetch keeps the requested live rows only")
        fun `selectByIds should return only the requested live servers`() {
            // Given - 下发链路一次批量取绑定到的服务，多给或漏给都会让 spec 与绑定表不一致
            val first = insertServer("Batch MCP 1", 1L)
            val second = insertServer("Batch MCP 2", 1L)
            insertServer("Batch MCP 3", 1L)
            mcpServerMapper.deleteById(second.id)

            // When
            val names = mcpServerMapper.selectByIds(listOf(first.id, second.id, 999_999L)).map { it.name }

            // Then
            assertEquals(listOf("Batch MCP 1"), names)
        }

        @Test
        @DisplayName("selectMcpServerList - Filter by tenant")
        fun `selectMcpServerList should filter by tenant`() {
            // Given
            insertServer("Tenant One MCP", 11L)
            insertServer("Tenant Two MCP", 22L)

            // When - 传了租户就只看得到自己的行
            val owned = mcpServerMapper.selectMcpServerList(null, null, null, "admin", 11L).map { it.name }
            val unfiltered = mcpServerMapper.selectMcpServerList(null, null, null, "admin").map { it.name }

            // Then
            assertTrue(owned.contains("Tenant One MCP"))
            assertFalse(owned.contains("Tenant Two MCP"))
            // 不传租户时整个条件不拼上
            assertTrue(unfiltered.containsAll(listOf("Tenant One MCP", "Tenant Two MCP")))
        }
    }
}
