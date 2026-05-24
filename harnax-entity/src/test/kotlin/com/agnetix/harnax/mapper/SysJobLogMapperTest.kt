package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SysJobLog
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
import kotlin.test.assertTrue

@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SysJobLogMapperTest {

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
    private lateinit var sysJobLogMapper: SysJobLogMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询任务日志")
        fun `selectById should return job log by id`() {
            val log = sysJobLogMapper.selectById(1L)
            assertNotNull(log)
            assertEquals(1L, log.id)
            assertEquals(1L, log.jobId)
            assertEquals("Data Sync Job", log.jobName)
            assertEquals("执行成功", log.jobMessage)
            assertEquals(1, log.status)
        }

        @Test
        @DisplayName("insert - 插入新任务日志")
        fun `insert should create new job log`() {
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newLog = SysJobLog().apply {
                jobId = 1L
                jobName = "Data Sync Job"
                jobGroup = "DEFAULT"
                jobMessage = "新日志"
                status = 1
                startTime = now
                endTime = now.plusSeconds(5)
            }

            val result = sysJobLogMapper.insert(newLog)
            assertEquals(1, result)
            assertTrue(newLog.id > 0)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectByJobId - 根据任务 ID 查询日志")
        fun `selectByJobId should return logs by job id`() {
            val logs = sysJobLogMapper.selectByJobId(1L)
            assertTrue(logs.isNotEmpty())
            logs.forEach {
                assertEquals(1L, it.jobId)
            }
        }
    }
}
