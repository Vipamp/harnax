package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Session
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
 * SessionMapper 集成测试
 *
 * @author agnetix
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SessionMapperTest {

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
    private lateinit var sessionMapper: SessionMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询会话")
        fun `selectById should return session by id`() {
            // When
            val session = sessionMapper.selectById(1L)

            // Then
            assertNotNull(session)
            assertEquals(1L, session.id)
            assertEquals("session-001", session.sessionId)
            assertEquals(1L, session.agentId)
            assertEquals("测试会话1", session.title)
            assertEquals(1, session.status)
            assertEquals(1, session.active)
        }

        @Test
        @DisplayName("selectById - 查询不存在的会话返回 null")
        fun `selectById should return null when session not exists`() {
            // When
            val session = sessionMapper.selectById(999L)

            // Then
            assertNull(session)
        }

        @Test
        @DisplayName("selectById - 不返回已删除的会话")
        fun `selectById should not return deleted session`() {
            // When
            val session = sessionMapper.selectById(5L)

            // Then
            assertNull(session)
        }

        @Test
        @DisplayName("insert - 插入新会话")
        fun `insert should create new session`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newSession = Session().apply {
                sessionId = "session-new"
                agentId = 1L
                title = "新会话"
                sessionDescription = "测试新会话"
                creator = "admin"
                status = 1
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = sessionMapper.insert(newSession)

            // Then
            assertEquals(1, result)
            assertTrue(newSession.id > 0)

            val insertedSession = sessionMapper.selectById(newSession.id)
            assertNotNull(insertedSession)
            assertEquals("session-new", insertedSession.sessionId)
        }

        @Test
        @DisplayName("updateById - 更新会话信息")
        fun `updateById should update session info`() {
            // Given
            val sessionId = 1L
            val session = sessionMapper.selectById(sessionId)
            assertNotNull(session)

            // When
            session.title = "更新后的标题"
            session.sessionDescription = "更新后的描述"
            session.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = sessionMapper.updateById(session)

            // Then
            assertEquals(1, result)
            val updatedSession = sessionMapper.selectById(sessionId)
            assertNotNull(updatedSession)
            assertEquals("更新后的标题", updatedSession.title)
        }

        @Test
        @DisplayName("deleteById - 逻辑删除会话")
        fun `deleteById should logically delete session`() {
            // Given
            val sessionId = 2L
            val sessionBefore = sessionMapper.selectById(sessionId)
            assertNotNull(sessionBefore)

            // When
            val result = sessionMapper.deleteById(sessionId)

            // Then
            assertEquals(1, result)
            val deletedSession = sessionMapper.selectById(sessionId)
            assertNull(deletedSession)
        }
    }

    @Nested
    @DisplayName("状态管理测试")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - 更新会话状态")
        fun `updateStatus should update session status`() {
            // Given
            val sessionId = 1L
            val newStatus = 0

            // When
            val result = sessionMapper.updateStatus(sessionId, newStatus)
            val updatedSession = sessionMapper.selectById(sessionId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedSession)
            assertEquals(newStatus, updatedSession.status)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectSessionList - 查询所有会话列表")
        fun `selectSessionList should return all sessions`() {
            // When
            val sessions = sessionMapper.selectSessionList(null, null, "admin")

            // Then
            assertTrue(sessions.isNotEmpty())
            assertTrue(sessions.size >= 4)
        }

        @Test
        @DisplayName("selectSessionList - 按关键词查询")
        fun `selectSessionList should filter by keyword`() {
            // When
            val sessions = sessionMapper.selectSessionList("测试", null, "admin")

            // Then
            assertTrue(sessions.isNotEmpty())
            sessions.forEach {
                assertTrue(it.title?.contains("测试") == true)
            }
        }

        @Test
        @DisplayName("selectSessionList - 按状态查询")
        fun `selectSessionList should filter by status`() {
            // When
            val sessions = sessionMapper.selectSessionList(null, 0, "admin")

            // Then
            assertTrue(sessions.isNotEmpty())
            sessions.forEach {
                assertEquals(0, it.status)
            }
        }

        @Test
        @DisplayName("countByTitle - 统计指定标题的会话数量")
        fun `countByTitle should count sessions by title`() {
            // When
            val count = sessionMapper.countByTitle("测试会话1")

            // Then
            assertEquals(1, count)
        }

        @Test
        @DisplayName("selectBySessionIdAndStatus - 根据会话 ID 和状态查询")
        fun `selectBySessionIdAndStatus should return session by session id and status`() {
            // When
            val session = sessionMapper.selectBySessionIdAndStatus("session-001", 1)

            // Then
            assertNotNull(session)
            assertEquals("session-001", session.sessionId)
            assertEquals(1, session.status)
        }

        @Test
        @DisplayName("selectByAgentId - 根据智能体 ID 查询会话")
        fun `selectByAgentId should return sessions by agent id`() {
            // When
            val sessions = sessionMapper.selectByAgentId(1L)

            // Then
            assertTrue(sessions.isNotEmpty())
            sessions.forEach {
                assertEquals(1L, it.agentId)
            }
        }
    }
}
