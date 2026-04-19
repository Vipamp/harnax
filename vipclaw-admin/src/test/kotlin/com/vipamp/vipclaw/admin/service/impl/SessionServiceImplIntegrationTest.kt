package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.mapper.SessionMapper
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
 * SessionServiceImpl 集成测试
 * 使用 Testcontainers 启动真实的 MySQL 容器进行测试
 *
 * @author vipamp
 * @since 2026-04-20
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SessionServiceImplIntegrationTest {

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
    private lateinit var sessionService: SessionServiceImpl

    @Autowired
    private lateinit var sessionMapper: SessionMapper

    @Nested
    @DisplayName("分页查询测试")
    inner class PaginationTests {

        @Test
        @DisplayName("getSessionPage - 正常分页查询")
        fun `getSessionPage should return paginated results`() {
            // When
            val page = sessionService.getSessionPage(null, null, 1, 2)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 3) // schema-test.sql 中有4条，但deleted的active=0
            assertEquals(2, page.size)
            assertEquals(1, page.current)
            assertEquals(2, page.records.size)
        }

        @Test
        @DisplayName("getSessionPage - 标题搜索")
        fun `getSessionPage should filter by title`() {
            // When
            val page = sessionService.getSessionPage("测试会话1", null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.title?.contains("测试会话1") == true })
        }

        @Test
        @DisplayName("getSessionPage - 智能体ID过滤")
        fun `getSessionPage should filter by agentId`() {
            // When
            val page = sessionService.getSessionPage(null, 2L, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.agentId == 2L })
        }
    }

    @Nested
    @DisplayName("查询会话详情测试")
    inner class GetSessionByIdTests {

        @Test
        @DisplayName("getSessionById - 查询存在的会话")
        fun `getSessionById should return session when exists`() {
            // When
            val session = sessionService.getSessionById(1L)

            // Then
            assertNotNull(session)
            assertEquals(1L, session?.id)
            assertEquals("session-001", session?.sessionId)
        }

        @Test
        @DisplayName("getSessionById - 查询不存在的会话返回null")
        fun `getSessionById should return null when session not found`() {
            // When
            val session = sessionService.getSessionById(999L)

            // Then
            assertNull(session)
        }
    }

    @Nested
    @DisplayName("根据SessionId查询测试")
    inner class GetBySessionIdTests {

        @Test
        @DisplayName("getBySessionId - 查询存在的会话")
        fun `getBySessionId should return session when exists`() {
            // When
            val session = sessionService.getBySessionId("session-001")

            // Then
            assertNotNull(session)
            assertEquals("session-001", session?.sessionId)
            assertEquals("测试会话1", session?.title)
        }

        @Test
        @DisplayName("getBySessionId - 查询不存在的会话返回null")
        fun `getBySessionId should return null when session not found`() {
            // When
            val session = sessionService.getBySessionId("nonexistent")

            // Then
            assertNull(session)
        }
    }

    @Nested
    @DisplayName("根据智能体ID查询会话列表测试")
    inner class GetByAgentIdTests {

        @Test
        @DisplayName("getByAgentId - 查询智能体的会话列表")
        fun `getByAgentId should return sessions for agent`() {
            // When
            val sessions = sessionService.getByAgentId(1L)

            // Then
            assertNotNull(sessions)
            assertTrue(sessions.size >= 2) // session-001, session-002, session-deleted
            assertTrue(sessions.all { it.agentId == 1L })
        }

        @Test
        @DisplayName("getByAgentId - 查询没有会话的智能体")
        fun `getByAgentId should return empty list for agent without sessions`() {
            // When
            val sessions = sessionService.getByAgentId(999L)

            // Then
            assertNotNull(sessions)
            assertTrue(sessions.isEmpty())
        }
    }

    @Nested
    @DisplayName("创建会话测试")
    inner class CreateSessionTests {

        @Test
        @DisplayName("createSession - 创建成功")
        fun `createSession should create session successfully`() {
            // When
            val result = sessionService.createSession(1L, "New Session", "新会话")

            // Then
            assertNotNull(result)
            assertTrue(result!!.id > 0)
            assertEquals("New Session", result.title)
            assertEquals("新会话", result.sessionDescription)
        }
    }

    @Nested
    @DisplayName("更新会话测试")
    inner class UpdateSessionTests {

        @Test
        @DisplayName("updateSession - 更新会话标题")
        fun `updateSession should update session title`() {
            // When
            val result = sessionService.updateSession(1L, "Updated Title", "更新后的描述")

            // Then
            assertTrue(result)

            val session = sessionMapper.selectById(1L)
            assertEquals("Updated Title", session?.title)
            assertEquals("更新后的描述", session?.sessionDescription)
        }

        @Test
        @DisplayName("updateSession - 会话不存在应该返回false")
        fun `updateSession should return false when session not found`() {
            // When
            val result = sessionService.updateSession(999L, "New Title", "New Description")

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("删除会话测试")
    inner class DeleteSessionTests {

        @Test
        @DisplayName("deleteSession - 逻辑删除成功")
        fun `deleteSession should logically delete session`() {
            // When
            val result = sessionService.deleteSession(2L)

            // Then
            assertTrue(result)

            val session = sessionMapper.selectById(2L)
            assertEquals(0, session?.active)
        }

        @Test
        @DisplayName("deleteSession - 删除不存在的会话")
        fun `deleteSession should return false when session not found`() {
            // When
            val result = sessionService.deleteSession(999L)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("统计智能体会话数测试")
    inner class CountByAgentIdTests {

        @Test
        @DisplayName("countByAgentId - 统计智能体会话数")
        fun `countByAgentId should count sessions for agent`() {
            // When
            val count = sessionService.countByAgentId(1L)

            // Then
            assertTrue(count >= 2) // session-001, session-002
        }

        @Test
        @DisplayName("countByAgentId - 统计没有会话的智能体")
        fun `countByAgentId should return 0 for agent without sessions`() {
            // When
            val count = sessionService.countByAgentId(999L)

            // Then
            assertEquals(0, count)
        }
    }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：创建 - 查询 - 更新 - 删除")
        fun `complete flow create query update delete`() {
            // 1. 创建会话
            val created = sessionService.createSession(1L, "FlowTest Session", "流程测试会话")
            assertNotNull(created)
            val sessionId = created!!.id

            // 2. 查询会话
            val session = sessionService.getSessionById(sessionId)
            assertNotNull(session)
            assertEquals("FlowTest Session", session?.title)

            // 3. 更新会话
            assertTrue(sessionService.updateSession(sessionId, "Updated FlowTest", "更新后的流程测试"))
            val updatedSession = sessionService.getSessionById(sessionId)
            assertEquals("Updated FlowTest", updatedSession?.title)
            assertEquals("更新后的流程测试", updatedSession?.sessionDescription)

            // 4. 删除会话
            assertTrue(sessionService.deleteSession(sessionId))
            val deletedSession = sessionMapper.selectById(sessionId)
            assertEquals(0, deletedSession?.active)
        }
    }
}
