package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTool
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
 * AgentToolMapper Integration Tests
 *
 * @author agnetix
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AgentToolMapperTest {

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
    private lateinit var agentToolMapper: AgentToolMapper

    @Nested
    @DisplayName("Basic CRUD Tests")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - Query AgentTool by ID")
        fun `selectById should return agent tool by id`() {
            // When
            val agentTool = agentToolMapper.selectById(1L)

            // Then
            assertNotNull(agentTool)
            assertEquals(1L, agentTool.id)
            assertEquals("time-tool-box", agentTool.name)
            assertEquals("BUILTIN", agentTool.type)
            assertEquals("time-tool-box", agentTool.beanName)
            assertEquals(0, agentTool.needConfirm)
            assertEquals(1, agentTool.status)
            assertEquals(1, agentTool.active)
        }

        @Test
        @DisplayName("selectById - Return null when AgentTool not exists")
        fun `selectById should return null when agent tool not exists`() {
            // When
            val agentTool = agentToolMapper.selectById(999L)

            // Then
            assertNull(agentTool)
        }

        @Test
        @DisplayName("selectById - Do not return deleted AgentTool")
        fun `selectById should not return deleted agent tool`() {
            // When
            val agentTool = agentToolMapper.selectById(5L)

            // Then
            assertNull(agentTool)
        }

        @Test
        @DisplayName("insert - Insert new AgentTool")
        fun `insert should create new agent tool`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newAgentTool = AgentTool().apply {
                name = "new-custom-tool"
                type = "CUSTOM"
                beanName = "new-custom-tool-box"
                needConfirm = 1
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = agentToolMapper.insert(newAgentTool)

            // Then
            assertEquals(1, result)
            assertTrue(newAgentTool.id > 0)

            val insertedAgentTool = agentToolMapper.selectById(newAgentTool.id)
            assertNotNull(insertedAgentTool)
            assertEquals("new-custom-tool", insertedAgentTool.name)
        }

        @Test
        @DisplayName("updateById - Update AgentTool info")
        fun `updateById should update agent tool info`() {
            // Given
            val agentToolId = 1L
            val agentTool = agentToolMapper.selectById(agentToolId)
            assertNotNull(agentTool)

            // When
            agentTool.name = "updated-tool"
            agentTool.beanName = "updated-tool-box"
            agentTool.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = agentToolMapper.updateById(agentTool)

            // Then
            assertEquals(1, result)
            val updatedAgentTool = agentToolMapper.selectById(agentToolId)
            assertNotNull(updatedAgentTool)
            assertEquals("updated-tool", updatedAgentTool.name)
        }

        @Test
        @DisplayName("deleteById - Logically delete AgentTool")
        fun `deleteById should logically delete agent tool`() {
            // Given
            val agentToolId = 2L
            val agentToolBefore = agentToolMapper.selectById(agentToolId)
            assertNotNull(agentToolBefore)

            // When
            val result = agentToolMapper.deleteById(agentToolId)

            // Then
            assertEquals(1, result)
            val deletedAgentTool = agentToolMapper.selectById(agentToolId)
            assertNull(deletedAgentTool)
        }
    }

    @Nested
    @DisplayName("Status Management Tests")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - Disable AgentTool")
        fun `updateStatus should disable agent tool`() {
            // Given
            val agentToolId = 1L
            val newStatus = 0

            // When
            val result = agentToolMapper.updateStatus(agentToolId, newStatus)
            val updatedAgentTool = agentToolMapper.selectById(agentToolId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedAgentTool)
            assertEquals(newStatus, updatedAgentTool.status)
        }

        @Test
        @DisplayName("updateStatus - Enable AgentTool")
        fun `updateStatus should enable agent tool`() {
            // Given
            val agentToolId = 4L
            val newStatus = 1

            // When
            val result = agentToolMapper.updateStatus(agentToolId, newStatus)
            val updatedAgentTool = agentToolMapper.selectById(agentToolId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedAgentTool)
            assertEquals(newStatus, updatedAgentTool.status)
        }
    }

    @Nested
    @DisplayName("Custom Query Tests")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectAgentToolList - Query all active AgentTools")
        fun `selectAgentToolList should return all active agent tools`() {
            // When
            val agentTools = agentToolMapper.selectAgentToolList(null, null, null)

            // Then
            assertTrue(agentTools.isNotEmpty())
            assertTrue(agentTools.size >= 4)
        }

        @Test
        @DisplayName("selectAgentToolList - Filter by keyword")
        fun `selectAgentToolList should filter by keyword`() {
            // When
            val agentTools = agentToolMapper.selectAgentToolList("tool", null, null)

            // Then
            assertTrue(agentTools.isNotEmpty())
            agentTools.forEach {
                assertTrue(it.name.contains("tool"))
            }
        }

        @Test
        @DisplayName("selectAgentToolList - Filter by status")
        fun `selectAgentToolList should filter by status`() {
            // When
            val agentTools = agentToolMapper.selectAgentToolList(null, 1, null)

            // Then
            assertTrue(agentTools.isNotEmpty())
            agentTools.forEach {
                assertEquals(1, it.status)
            }
        }

        @Test
        @DisplayName("selectAgentToolList - Filter by type")
        fun `selectAgentToolList should filter by type`() {
            // When
            val agentTools = agentToolMapper.selectAgentToolList(null, null, "BUILTIN")

            // Then
            assertTrue(agentTools.isNotEmpty())
            agentTools.forEach {
                assertEquals("BUILTIN", it.type)
            }
        }

        @Test
        @DisplayName("selectByName - Query AgentTool by name")
        fun `selectByName should return agent tool by name`() {
            // When
            val agentTool = agentToolMapper.selectByName("weather-tool")

            // Then
            assertNotNull(agentTool)
            assertEquals("weather-tool", agentTool.name)
        }

        @Test
        @DisplayName("selectByName - Return null when name not exists")
        fun `selectByName should return null when name not exists`() {
            // When
            val agentTool = agentToolMapper.selectByName("nonexistent-tool")

            // Then
            assertNull(agentTool)
        }

        @Test
        @DisplayName("selectAllEnabled - Return only enabled and active AgentTools")
        fun `selectAllEnabled should return only enabled and active agent tools`() {
            // When
            val agentTools = agentToolMapper.selectAllEnabled()

            // Then
            assertTrue(agentTools.isNotEmpty())
            agentTools.forEach {
                assertEquals(1, it.status)
                assertEquals(1, it.active)
            }
        }
    }
}
