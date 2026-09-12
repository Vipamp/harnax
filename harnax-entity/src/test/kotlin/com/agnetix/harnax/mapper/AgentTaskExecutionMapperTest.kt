package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTaskExecution
import org.junit.jupiter.api.DisplayName
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

/**
 * A guard row left at status 0 is not just stale data: it is a lock nobody holds.
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class AgentTaskExecutionMapperTest {

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
    private lateinit var executionMapper: AgentTaskExecutionMapper

    @Test
    @DisplayName("deleteStaleRunning - 只删除超时仍未定态的抢锁行")
    fun `deleteStaleRunning should only remove locked rows past the deadline`() {
        val leaked = insertExecution(taskId = 1L, status = 0, ageSeconds = 900L, triggerTime = LocalDateTime.now())
        val held = insertExecution(taskId = 2L, status = 0, ageSeconds = 30L, triggerTime = LocalDateTime.now())
        val done = insertExecution(taskId = 3L, status = 1, ageSeconds = 900L, triggerTime = LocalDateTime.now())

        val deleted = executionMapper.deleteStaleRunning(LocalDateTime.now().minusSeconds(600))
        assertEquals(1, deleted)

        assertEquals(null, executionMapper.selectByTaskIdAndTriggerTime(leaked.taskId, leaked.triggerTime))
        // 被持有的锁必须还在——删了它就等于允许同一触发时间在两个节点上各跑一次
        assertEquals(held.id, executionMapper.selectByTaskIdAndTriggerTime(held.taskId, held.triggerTime)?.id)
        // 已定态的老行也不归这里清，那是保留期清理的职责
        assertEquals(done.id, executionMapper.selectByTaskIdAndTriggerTime(done.taskId, done.triggerTime)?.id)
    }

    private fun insertExecution(
        taskId: Long,
        status: Int,
        ageSeconds: Long,
        triggerTime: LocalDateTime,
    ): AgentTaskExecution = AgentTaskExecution().apply {
        this.taskId = taskId
        this.triggerTime = triggerTime.withNano(0)
        instanceId = "test-instance"
        this.status = status
        createTime = LocalDateTime.now().minusSeconds(ageSeconds)
        executionMapper.insert(this)
    }
}
