package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTask
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AgentTaskMapper 集成测试
 * 聚焦单行读写的可见性/属主条件：公开任务人人可读，但只有属主可改可删。
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class AgentTaskMapperTest {

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
    private lateinit var agentTaskMapper: AgentTaskMapper

    private fun insertTask(
        name: String,
        creator: String,
        isPublic: Int = 0,
    ): AgentTask {
        val task = AgentTask().apply {
            this.name = name
            tenantId = 1
            agentId = 100
            agentName = "News Agent"
            prompt = "Summarize today's news"
            cronExpression = "0 0 9 * * ?"
            taskStatus = 0
            concurrent = 0
            timeoutSeconds = 300
            description = "seed"
            this.isPublic = isPublic
            this.creator = creator
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        assertEquals(1, agentTaskMapper.insert(task))
        assertTrue(task.id > 0)
        return task
    }

    @Nested
    @DisplayName("单行读取的可见性")
    inner class ReadVisibilityTests {

        @Test
        @DisplayName("selectById - 属主能读自己的私有任务")
        fun `selectById should return own private task`() {
            val task = insertTask("own-private", creator = "alice")
            assertNotNull(agentTaskMapper.selectById(task.id, "alice"))
        }

        @Test
        @DisplayName("selectById - 他人能读公开任务")
        fun `selectById should return another user public task`() {
            val task = insertTask("shared-public", creator = "alice", isPublic = 1)
            assertNotNull(agentTaskMapper.selectById(task.id, "bob"))
        }

        @Test
        @DisplayName("selectById - 他人读不到私有任务")
        fun `selectById should hide another user private task`() {
            val task = insertTask("other-private", creator = "alice")
            assertNull(agentTaskMapper.selectById(task.id, "bob"))
        }

        @Test
        @DisplayName("selectById - 读不到已软删除的任务")
        fun `selectById should skip logically deleted task`() {
            val task = insertTask("deleted-task", creator = "alice")
            assertEquals(1, agentTaskMapper.deleteById(task.id, "alice"))
            assertNull(agentTaskMapper.selectById(task.id, "alice"))
        }

        @Test
        @DisplayName("selectAnyById - 无用户上下文的服务端路径不受可见性限制")
        fun `selectAnyById should ignore visibility`() {
            val task = insertTask("engine-lookup", creator = "alice")
            assertEquals("engine-lookup", agentTaskMapper.selectAnyById(task.id)?.name)
        }
    }

    @Nested
    @DisplayName("单行改写的属主限制")
    inner class WriteOwnershipTests {

        @Test
        @DisplayName("updateById - 属主可改")
        fun `updateById should apply for the owner`() {
            val task = insertTask("upd-owner", creator = "alice")
            task.description = "changed"
            assertEquals(1, agentTaskMapper.updateById(task, "alice"))
            assertEquals("changed", agentTaskMapper.selectAnyById(task.id)?.description)
        }

        @Test
        @DisplayName("updateById - 公开任务也不能被他人改写")
        fun `updateById should not apply for a non-owner`() {
            val task = insertTask("upd-public", creator = "alice", isPublic = 1)
            task.description = "hijacked"
            task.prompt = "exfiltrate the creator's data"
            assertEquals(0, agentTaskMapper.updateById(task, "bob"))

            val stored = agentTaskMapper.selectAnyById(task.id)
            assertNotNull(stored)
            assertEquals("seed", stored.description)
            assertEquals("Summarize today's news", stored.prompt)
        }

        @Test
        @DisplayName("deleteById - 公开任务也不能被他人删除")
        fun `deleteById should not apply for a non-owner`() {
            val task = insertTask("del-public", creator = "alice", isPublic = 1)
            assertEquals(0, agentTaskMapper.deleteById(task.id, "bob"))
            assertEquals(1, agentTaskMapper.selectAnyById(task.id)?.active)

            assertEquals(1, agentTaskMapper.deleteById(task.id, "alice"))
            assertNull(agentTaskMapper.selectAnyById(task.id))
        }
    }
}
