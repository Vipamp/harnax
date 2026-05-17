package com.agnetix.harnax.admin.mapper

import com.agnetix.harnax.admin.entity.ProcessLogEntity
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
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
import kotlin.test.assertTrue

@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProcessLogMapperTest {

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
    private lateinit var processLogMapper: ProcessLogMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询日志")
        fun `selectById should return process log by id`() {
            val log = processLogMapper.selectById(1L)
            assertNotNull(log)
            assertEquals(1L, log.id)
            assertEquals(1L, log.agentId)
            assertEquals("Test Agent 1", log.agentName)
            assertEquals("开始处理请求", log.message)
            assertEquals("INFO", log.logType)
        }

        @Test
        @DisplayName("selectById - 查询不存在的日志返回 null")
        fun `selectById should return null when log not exists`() {
            val log = processLogMapper.selectById(999L)
            assertNull(log)
        }

        @Test
        @DisplayName("insert - 插入新日志")
        fun `insert should create new process log`() {
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newLog = ProcessLogEntity().apply {
                agentId = 1L
                agentName = "Test Agent 1"
                sessionId = "session-001"
                message = "新日志消息"
                logType = "INFO"
                ts = now
            }

            val result = processLogMapper.insert(newLog)
            assertEquals(1, result)
            assertTrue(newLog.id > 0)

            val insertedLog = processLogMapper.selectById(newLog.id)
            assertNotNull(insertedLog)
            assertEquals("新日志消息", insertedLog.message)
        }
    }
}
