package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.SessionCreateRequest
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SessionMapper
import org.junit.jupiter.api.*
import org.junit.jupiter.api.assertThrows
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
        @DisplayName("getSessionPage - 智能体ID过滤 - 跳过（Service不支持此过滤）")
        fun `getSessionPage filter by agentId - skipped`() {
            // SessionService的getSessionPage不支持agentId过滤
            // 此测试跳过
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

    // getBySessionId和getByAgentId方法在SessionService中不存在，注释掉这些测试
    // @Nested
    // @DisplayName("根据SessionId查询测试")
    // inner class GetBySessionIdTests { ... }
    
    // @Nested
    // @DisplayName("根据智能体ID查询会话列表测试")
    // inner class GetByAgentIdTests { ... }

    @Nested
    @DisplayName("创建会话测试")
    inner class CreateSessionTests {

        @Test
        @DisplayName("createSession - 创建成功")
        fun `createSession should create session successfully`() {
            // Given
            val request = SessionCreateRequest(
                title = "New Session",
                sessionDescription = "新会话",
                agentId = 1L
            )
            
            // When
            val result = sessionService.createSession(request)

            // Then
            assertTrue(result)
        }
    }

    // SessionService没有updateSession方法，注释掉这些测试
    // @Nested
    // @DisplayName("更新会话测试")
    // inner class UpdateSessionTests { ... }

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
        @DisplayName("deleteSession - 删除不存在的会话应该抛出异常")
        fun `deleteSession should throw BizException when session not found`() {
            // When & Then
            assertThrows<BizException> {
                sessionService.deleteSession(999L)
            }
        }
    }

    // SessionService没有countByAgentId方法，注释掉这些测试
    // @Nested
    // @DisplayName("统计智能体会话数测试")
    // inner class CountByAgentIdTests { ... }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：创建 - 查询 - 删除")
        fun `complete flow create query delete`() {
            // 1. 创建会话
            val request = SessionCreateRequest(
                title = "FlowTest Session",
                sessionDescription = "流程测试会话",
                agentId = 1L
            )
            assertTrue(sessionService.createSession(request))
            
            // 2. 查询会话（通过分页查询）
            val page = sessionService.getSessionPage("FlowTest Session", null, 1, 10)
            assertTrue(page.total >= 1)
            val sessionId = page.records[0].id

            // 3. 删除会话
            assertTrue(sessionService.deleteSession(sessionId))
            val deletedSession = sessionMapper.selectById(sessionId)
            assertEquals(0, deletedSession?.active)
        }
    }
}
