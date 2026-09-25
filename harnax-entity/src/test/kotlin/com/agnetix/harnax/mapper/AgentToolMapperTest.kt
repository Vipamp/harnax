package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTool
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AgentToolMapper Integration Tests
 *
 * The table is platform-scoped and additive (V40): the sync resolves a declaration by `name`, so the
 * write-side cases here go through `insert` / `updateById` — there is no delete on this mapper.
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

    /** A row as the code sync would write it; `name` is the identity, bean/method only serve instantiation. */
    private fun syncedTool(name: String, beanName: String, methodName: String = name): AgentTool = AgentTool().apply {
        this.name = name
        displayName = "Synced $name"
        description = "synced by test"
        this.beanName = beanName
        this.methodName = methodName
        readOnly = 0
        needConfirm = 0
        isRequired = 0
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
        @DisplayName("selectById - Do not return an inactive AgentTool")
        fun `selectById should not return inactive agent tool`() {
            // Seed id=6 is a soft-deleted leftover (active=0); id=5 is disabled-tool, only status=0, so it stays readable
            assertNull(agentToolMapper.selectById(6L))
            assertNotNull(agentToolMapper.selectById(5L))
        }

        @Test
        @DisplayName("selectByIds - Batch load keeps only active rows")
        fun `selectByIds should skip inactive rows`() {
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
        @DisplayName("selectByName - Also finds an inactive row, because it is the sync's identity lookup")
        fun `selectByName should find an inactive row`() {
            // The retired manual delete left rows at active = 0. The name is still taken: missing it
            // would make the sync insert a second row under uk_agent_tool_name instead of reviving this one.
            val agentTool = agentToolMapper.selectByName("deleted-tool")

            assertNotNull(agentTool)
            assertEquals(6L, agentTool.id)
            assertEquals(0, agentTool.active)
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
            // id=6 is inactive, so the seed leaves four queryable rows
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

            val mandatory = syncedTool("mandatory_tool", "mandatory-tool-box", "doMandatory").apply {
                isRequired = 1
            }
            agentToolMapper.insert(mandatory)

            val tools = agentToolMapper.selectRequiredTools()
            assertEquals(1, tools.size)
            assertEquals("mandatory_tool", tools[0].name)
        }
    }

    @Nested
    @DisplayName("Sync Write Path")
    inner class SyncWritePath {

        @Test
        @DisplayName("insert - Stamps the row as the sync would, creator included")
        fun `insert should write an enabled row stamped by the system`() {
            val tool = syncedTool("sync_tool", "sync-tool-box", "syncMethod").apply { creator = "someone" }

            assertEquals(1, agentToolMapper.insert(tool))
            assertTrue(tool.id > 0)

            val inserted = agentToolMapper.selectByName("sync_tool")
            assertNotNull(inserted)
            assertEquals("SYSTEM", inserted.creator)
            assertEquals(1, inserted.status)
            assertEquals(1, inserted.active)
        }

        @Test
        @DisplayName("insert - The name is the identity: a second row with it is refused")
        fun `insert should refuse a duplicate name`() {
            agentToolMapper.insert(syncedTool("identity_tool", "a-tool-box", "run"))

            assertFailsWith<DuplicateKeyException>("expected uk_agent_tool_name to refuse the second row") {
                agentToolMapper.insert(syncedTool("identity_tool", "b-tool-box", "run"))
            }
        }

        @Test
        @DisplayName("updateById - Refreshes the code-owned columns and leaves the identity alone")
        fun `updateById should refresh the code-owned columns`() {
            agentToolMapper.insert(syncedTool("refresh_tool", "refresh-tool-box", "run"))
            val inserted = requireNotNull(agentToolMapper.selectByName("refresh_tool"))

            val desired = syncedTool("refresh_tool", "moved-tool-box", "movedMethod").apply {
                id = inserted.id
                description = "changed by the next startup"
                needConfirm = 1
            }
            assertEquals(1, agentToolMapper.updateById(desired))

            val refreshed = agentToolMapper.selectByName("refresh_tool")
            assertNotNull(refreshed)
            assertEquals(inserted.id, refreshed.id)
            assertEquals("changed by the next startup", refreshed.description)
            assertEquals(1, refreshed.needConfirm)
            assertEquals("moved-tool-box", refreshed.beanName)
            assertEquals("SYSTEM", refreshed.creator)
        }

        @Test
        @DisplayName("updateById - Revives a legacy inactive row instead of leaving it out of delivery")
        fun `updateById should revive an inactive row`() {
            val legacy = requireNotNull(agentToolMapper.selectByName("deleted-tool"))
            assertEquals(0, legacy.active)

            val desired = syncedTool("deleted-tool", "revived-tool-box", "run").apply { id = legacy.id }
            assertEquals(1, agentToolMapper.updateById(desired))

            val revived = agentToolMapper.selectByName("deleted-tool")
            assertEquals(1, revived?.active)
            assertNotNull(agentToolMapper.selectById(legacy.id))
        }
    }
}
