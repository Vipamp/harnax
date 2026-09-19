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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AgentToolMapper Integration Tests
 *
 * The table is written only by the startup sync, so the write-side cases here go through
 * upsertBuiltinTool / deleteBuiltinByIds instead of a CRUD surface.
 *
 * @author agnetix
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class AgentToolMapperTest {

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

    /** A row as the code sync would write it; bean/method pick the uk_tenant_bean_method slot. */
    private fun syncedTool(beanName: String, methodName: String, name: String = methodName): AgentTool = AgentTool().apply {
        tenantId = 1L
        this.name = name
        displayName = "Synced $name"
        description = "synced by test"
        this.beanName = beanName
        this.methodName = methodName
        readOnly = 0
        needConfirm = 0
        isRequired = 0
        timeoutSeconds = 30
        status = 1
        active = 1
    }

    @Nested
    @DisplayName("Single Row Queries")
    inner class SingleRowQueries {

        @Test
        @DisplayName("selectById - Query AgentTool by ID")
        fun `selectById should return agent tool by id`() {
            // When
            val agentTool = agentToolMapper.selectById(1L)

            // Then
            assertNotNull(agentTool)
            assertEquals(1L, agentTool.id)
            assertEquals("getDate", agentTool.name)
            assertEquals("time-tool-box", agentTool.beanName)
            assertEquals(0, agentTool.needConfirm)
            assertEquals(1, agentTool.status)
            assertEquals(1, agentTool.active)
        }

        @Test
        @DisplayName("selectById - Return null when AgentTool not exists")
        fun `selectById should return null when agent tool not exists`() {
            assertNull(agentToolMapper.selectById(999L))
        }

        @Test
        @DisplayName("selectById - Do not return deleted AgentTool")
        fun `selectById should not return deleted agent tool`() {
            // 种子数据 id=6 是 deleted-tool（active=0）；id=5 是 disabled-tool，只是 status=0，仍然可查
            assertNull(agentToolMapper.selectById(6L))
            assertNotNull(agentToolMapper.selectById(5L))
        }

        @Test
        @DisplayName("selectByIds - Batch load keeps only active rows")
        fun `selectByIds should skip deleted rows`() {
            val tools = agentToolMapper.selectByIds(listOf(1L, 2L, 6L))

            assertEquals(2, tools.size)
            assertTrue(tools.none { it.id == 6L })
        }

        @Test
        @DisplayName("selectByName - Query AgentTool by name")
        fun `selectByName should return agent tool by name`() {
            val agentTool = agentToolMapper.selectByName("weather-tool")

            assertNotNull(agentTool)
            assertEquals("weather-tool", agentTool.name)
        }

        @Test
        @DisplayName("selectByName - Return null when name not exists")
        fun `selectByName should return null when name not exists`() {
            assertNull(agentToolMapper.selectByName("nonexistent-tool"))
        }

        @Test
        @DisplayName("selectByBeanName - One record per @Tool method")
        fun `selectByBeanName should return every method of the toolbox`() {
            val tools = agentToolMapper.selectByBeanName("time-tool-box")

            assertEquals(2, tools.size)
            assertTrue(tools.all { it.beanName == "time-tool-box" })
        }
    }

    @Nested
    @DisplayName("List Queries")
    inner class ListQueries {

        @Test
        @DisplayName("selectAgentToolList - Query all active AgentTools")
        fun `selectAgentToolList should return all active agent tools`() {
            val agentTools = agentToolMapper.selectAgentToolList(null, null)

            assertTrue(agentTools.isNotEmpty())
            // id=6 is soft-deleted, so the seed leaves four queryable rows
            assertEquals(4, agentTools.size)
            assertTrue(agentTools.none { it.id == 6L })
        }

        @Test
        @DisplayName("selectAgentToolList - Filter by keyword")
        fun `selectAgentToolList should filter by keyword`() {
            val agentTools = agentToolMapper.selectAgentToolList("tool", null)

            assertTrue(agentTools.isNotEmpty())
            assertTrue(agentTools.all { it.name.contains("tool") })
        }

        @Test
        @DisplayName("selectAgentToolList - Filter by status")
        fun `selectAgentToolList should filter by status`() {
            val agentTools = agentToolMapper.selectAgentToolList(null, 1)

            assertTrue(agentTools.isNotEmpty())
            agentTools.forEach { assertEquals(1, it.status) }
        }

        @Test
        @DisplayName("selectAvailableTools - Enabled and non-mandatory only")
        fun `selectAvailableTools should exclude disabled and mandatory tools`() {
            val tools = agentToolMapper.selectAvailableTools()

            assertTrue(tools.isNotEmpty())
            tools.forEach {
                assertEquals(1, it.status)
                assertEquals(0, it.isRequired)
            }
            assertTrue(tools.none { it.id == 5L })
        }

        @Test
        @DisplayName("selectBuiltinToolList - Every active row regardless of status")
        fun `selectBuiltinToolList should return every active tool`() {
            val tools = agentToolMapper.selectBuiltinToolList()

            assertEquals(4, tools.size)
            assertTrue(tools.any { it.id == 5L })
        }

        @Test
        @DisplayName("selectRequiredTools - Only enabled mandatory tools")
        fun `selectRequiredTools should return mandatory tools only`() {
            assertTrue(agentToolMapper.selectRequiredTools().isEmpty())

            val mandatory = syncedTool("mandatory-tool-box", "doMandatory", name = "mandatory_tool").apply {
                isRequired = 1
            }
            agentToolMapper.upsertBuiltinTool(mandatory)

            val tools = agentToolMapper.selectRequiredTools()
            assertEquals(1, tools.size)
            assertEquals("mandatory_tool", tools[0].name)
        }
    }

    @Nested
    @DisplayName("Sync Write Path")
    inner class SyncWritePath {

        @Test
        @DisplayName("upsertBuiltinTool - Insert then refresh in place on the same key")
        fun `upsertBuiltinTool should insert and then update the same row`() {
            val tool = syncedTool("sync-tool-box", "syncMethod")
            assertEquals(1, agentToolMapper.upsertBuiltinTool(tool))

            val inserted = agentToolMapper.selectByName("syncMethod")
            assertNotNull(inserted)
            assertTrue(inserted.id > 0)

            // Second pass on the same (tenant, bean, method): the row is refreshed, not duplicated
            inserted.description = "changed by the next startup"
            assertEquals(2, agentToolMapper.upsertBuiltinTool(inserted))

            val refreshed = agentToolMapper.selectByName("syncMethod")
            assertEquals(inserted.id, refreshed?.id)
            assertEquals("changed by the next startup", refreshed?.description)
            assertEquals(1, agentToolMapper.selectByBeanName("sync-tool-box").size)
        }

        @Test
        @DisplayName("upsertBuiltinTool - Creator is the sync, never the caller")
        fun `upsertBuiltinTool should stamp the system creator`() {
            agentToolMapper.upsertBuiltinTool(syncedTool("creator-tool-box", "syncMethod").apply { creator = "someone" })

            assertEquals("SYSTEM", agentToolMapper.selectByName("syncMethod")?.creator)
        }

        @Test
        @DisplayName("selectAllBuiltin - Includes soft-deleted rows so the sync can prune residue")
        fun `selectAllBuiltin should include inactive rows`() {
            val all = agentToolMapper.selectAllBuiltin()

            assertTrue(all.any { it.id == 6L })
        }

        @Test
        @DisplayName("deleteBuiltinByIds - Hard delete")
        fun `deleteBuiltinByIds should remove rows`() {
            agentToolMapper.upsertBuiltinTool(syncedTool("gone-tool-box", "syncMethod"))
            val id = requireNotNull(agentToolMapper.selectByName("syncMethod")?.id)

            assertEquals(1, agentToolMapper.deleteBuiltinByIds(listOf(id)))

            assertNull(agentToolMapper.selectByName("syncMethod"))
            assertNull(agentToolMapper.selectById(id))
        }
    }
}
