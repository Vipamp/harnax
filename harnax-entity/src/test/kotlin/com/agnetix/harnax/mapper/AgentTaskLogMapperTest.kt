package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTaskLog
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
import kotlin.test.assertTrue

/**
 * AgentTaskLogMapper 集成测试
 * 使用 Testcontainers 启动真实 MySQL 容器，验证 MyBatis XML 映射的正确性
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class AgentTaskLogMapperTest {

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
    private lateinit var agentTaskLogMapper: AgentTaskLogMapper

    // ==================== selectById ====================

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询日志")
        fun `selectById should return log by id`() {
            val log = agentTaskLogMapper.selectById(1L)
            assertNotNull(log)
            assertEquals(1L, log.id)
            assertEquals(1L, log.taskId)
            assertEquals("Daily News", log.taskName)
            assertEquals("Summarize today's news", log.prompt)
            assertEquals(1, log.status)
        }

        @Test
        @DisplayName("selectById - 查询不存在的日志返回 null")
        fun `selectById should return null when not exists`() {
            val log = agentTaskLogMapper.selectById(999L)
            kotlin.test.assertNull(log)
        }

        @Test
        @DisplayName("insert - 插入新日志记录")
        fun `insert should create new log`() {
            val newLog = AgentTaskLog().apply {
                taskId = 1L
                taskName = "Daily News"
                prompt = "Test prompt"
                response = "Test response"
                sessionId = "sess-test"
                status = 1
                errorInfo = ""
                tokenUsage = """{"input":50}"""
                startTime = LocalDateTime.now()
                endTime = LocalDateTime.now().plusSeconds(10)
                durationMs = 10000
                creator = "tester"
                createTime = LocalDateTime.now()
            }

            val result = agentTaskLogMapper.insert(newLog)
            assertEquals(1, result)
            assertTrue(newLog.id > 0)

            val inserted = agentTaskLogMapper.selectById(newLog.id)
            assertNotNull(inserted)
            assertEquals("Test prompt", inserted.prompt)
        }

        @Test
        @DisplayName("markStopping - 只有运行中的日志能进入停止中")
        fun `markStopping should only claim a running log`() {
            val running = insertExecutionLog(taskId = 1L, initialStatus = 3)

            assertEquals(1, agentTaskLogMapper.markStopping(running.id, "Stopping..."))
            assertEquals(4, agentTaskLogMapper.selectById(running.id)?.status)

            // 重复点击停止：状态已经不是 3，由调用方按幂等处理
            assertEquals(0, agentTaskLogMapper.markStopping(running.id, "Stopping..."))

            // 已结束的 seed 行（id=1, status=1）不能被拉回停止中
            assertEquals(0, agentTaskLogMapper.markStopping(1L, "Stopping..."))
        }

        @Test
        @DisplayName("finishExecution - 只在运行中回写，被请求停止后写入 0 行")
        fun `finishExecution should not overwrite a row that was asked to stop`() {
            val running = insertExecutionLog(taskId = 1L, initialStatus = 3)
            running.status = 1
            running.response = "Done"
            running.endTime = LocalDateTime.now()
            running.durationMs = 1000L
            assertEquals(1, agentTaskLogMapper.finishExecution(running))
            assertEquals(1, agentTaskLogMapper.selectById(running.id)?.status)

            // 执行线程收尾前用户点了停止：结果不能覆盖停止信号
            val stopping = insertExecutionLog(taskId = 1L, initialStatus = 3)
            agentTaskLogMapper.markStopping(stopping.id, "Stopping...")
            stopping.status = 1
            stopping.response = "Late result"
            assertEquals(0, agentTaskLogMapper.finishExecution(stopping))
            assertEquals(4, agentTaskLogMapper.selectById(stopping.id)?.status)
        }

        @Test
        @DisplayName("finalizeStopped - 停止中收尾为已停止(5)")
        fun `finalizeStopped should close a stopping row as stopped`() {
            val stopping = insertExecutionLog(taskId = 1L, initialStatus = 3)
            agentTaskLogMapper.markStopping(stopping.id, "Stopping...")

            stopping.status = 5
            stopping.endTime = LocalDateTime.now()
            stopping.durationMs = 2000L
            stopping.errorInfo = "Task stopped by user"
            assertEquals(1, agentTaskLogMapper.finalizeStopped(stopping))

            val updated = agentTaskLogMapper.selectById(stopping.id)
            assertNotNull(updated)
            assertEquals(5, updated.status)
            assertEquals(2000L, updated.durationMs)

            // 仍在运行(3)的行不会被 4 -> 5 这条语句碰到
            val running = insertExecutionLog(taskId = 1L, initialStatus = 3)
            assertEquals(0, agentTaskLogMapper.finalizeStopped(running))
        }

        @Test
        @DisplayName("expireStale - 按各任务自己的 timeout_seconds 回收残留")
        fun `expireStale should expire only rows past their own task timeout`() {
            // seed: task 1 的 timeout_seconds = 300, task 2 的 timeout_seconds = 600
            val stale = insertExecutionLog(taskId = 1L, initialStatus = 3, startedSecondsAgo = 400L)
            val staleStopping = insertExecutionLog(taskId = 1L, initialStatus = 4, startedSecondsAgo = 400L)
            val fresh = insertExecutionLog(taskId = 1L, initialStatus = 3)
            val withinLongerTimeout = insertExecutionLog(taskId = 2L, initialStatus = 3, startedSecondsAgo = 400L)

            val expired = agentTaskLogMapper.expireStale(300)
            assertTrue(expired >= 2, "至少两条残留应被回收, 实际=$expired")

            assertEquals(2, agentTaskLogMapper.selectById(stale.id)?.status)
            assertEquals(2, agentTaskLogMapper.selectById(staleStopping.id)?.status)
            assertEquals(3, agentTaskLogMapper.selectById(fresh.id)?.status)
            assertEquals(3, agentTaskLogMapper.selectById(withinLongerTimeout.id)?.status)
        }

        private fun insertExecutionLog(
            taskId: Long,
            initialStatus: Int,
            startedSecondsAgo: Long = 0L,
        ): AgentTaskLog {
            val log = AgentTaskLog().apply {
                this.taskId = taskId
                taskName = "Daily News"
                prompt = "Running prompt"
                response = ""
                sessionId = "sess-$initialStatus-$startedSecondsAgo-${System.nanoTime()}"
                status = initialStatus
                errorInfo = ""
                startTime = LocalDateTime.now().minusSeconds(startedSecondsAgo)
                creator = "admin"
            }
            agentTaskLogMapper.insert(log)
            return log
        }

        @Test
        @DisplayName("selectByTaskId - 根据任务 ID 查询日志列表")
        fun `selectByTaskId should return logs for given task`() {
            val logs = agentTaskLogMapper.selectByTaskId(1L)
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.size >= 3) // 测试数据中有 task_id=1 的 4 条记录 (id=1,2,3,5)
            logs.forEach {
                assertEquals(1L, it.taskId)
            }
        }

        @Test
        @DisplayName("selectByTaskId - 不存在的任务返回空列表")
        fun `selectByTaskId should return empty for non-existent task`() {
            val logs = agentTaskLogMapper.selectByTaskId(999L)
            assertTrue(logs.isEmpty())
        }
    }

    // ==================== selectLogList ====================

    @Nested
    @DisplayName("selectLogList 筛选查询测试")
    inner class SelectLogListTests {

        @Test
        @DisplayName("仅按 taskId 筛选")
        fun `selectLogList should filter by taskId`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = 1L,
                taskName = null,
                status = null,
                startTimeFrom = null,
                startTimeTo = null,
                keyword = null,
            )
            assertTrue(logs.isNotEmpty())
            logs.forEach { assertEquals(1L, it.taskId) }
        }

        @Test
        @DisplayName("按 taskId + status 组合筛选")
        fun `selectLogList should filter by taskId and status`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = 1L,
                taskName = null,
                status = 1,
                startTimeFrom = null,
                startTimeTo = null,
                keyword = null,
            )
            assertTrue(logs.isNotEmpty())
            logs.forEach {
                assertEquals(1L, it.taskId)
                assertEquals(1, it.status)
            }
        }

        @Test
        @DisplayName("按 taskName 模糊搜索")
        fun `selectLogList should filter by taskName fuzzy`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = null,
                taskName = "Daily",
                status = null,
                startTimeFrom = null,
                startTimeTo = null,
                keyword = null,
            )
            assertTrue(logs.isNotEmpty())
            logs.forEach { assertTrue(it.taskName.contains("Daily")) }
        }

        @Test
        @DisplayName("按时间范围筛选 startTimeFrom")
        fun `selectLogList should filter by startTimeFrom`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = 1L,
                taskName = null,
                status = null,
                startTimeFrom = "2026-07-02 00:00:00",
                startTimeTo = null,
                keyword = null,
            )
            assertTrue(logs.isNotEmpty())
            logs.forEach {
                assertNotNull(it.startTime)
                // 用 LocalDateTime 比，不要拿 toString() 和「只有日期」的字符串按字典序比：
                // "2026-07-01T09:00" 比 "2026-07-01" 长且同前缀，会被判成更大
                assertTrue(!it.startTime!!.isBefore(LocalDateTime.of(2026, 7, 2, 0, 0)))
            }
        }

        @Test
        @DisplayName("按时间范围筛选 startTimeTo")
        fun `selectLogList should filter by startTimeTo`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = 1L,
                taskName = null,
                status = null,
                startTimeFrom = null,
                startTimeTo = "2026-07-01 23:59:59",
                keyword = null,
            )
            assertTrue(logs.isNotEmpty())
            logs.forEach {
                assertNotNull(it.startTime)
                assertTrue(!it.startTime!!.isAfter(LocalDateTime.of(2026, 7, 1, 23, 59, 59)))
            }
        }

        @Test
        @DisplayName("按完整时间范围筛选 startTimeFrom + startTimeTo")
        fun `selectLogList should filter by time range`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = 1L,
                taskName = null,
                status = null,
                startTimeFrom = "2026-07-01 00:00:00",
                startTimeTo = "2026-07-02 23:59:59",
                keyword = null,
            )
            assertTrue(logs.isNotEmpty())
            // 应该匹配 id=1 (07-01) 和 id=2 (07-02)
            assertEquals(2, logs.size)
        }

        @Test
        @DisplayName("按关键词搜索 prompt 字段")
        fun `selectLogList should search keyword in prompt`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = null,
                taskName = null,
                status = null,
                startTimeFrom = null,
                startTimeTo = null,
                keyword = "breaking",
            )
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.any { it.prompt.contains("breaking", ignoreCase = true) })
        }

        @Test
        @DisplayName("按关键词搜索 response 字段")
        fun `selectLogList should search keyword in response`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = null,
                taskName = null,
                status = null,
                startTimeFrom = null,
                startTimeTo = null,
                keyword = "Weekly report content",
            )
            assertTrue(logs.isNotEmpty())
            assertEquals(4L, logs[0].id)
        }

        @Test
        @DisplayName("按关键词搜索 error_info 字段")
        fun `selectLogList should search keyword in error_info`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = null,
                taskName = null,
                status = null,
                startTimeFrom = null,
                startTimeTo = null,
                keyword = "Connection timeout",
            )
            assertTrue(logs.isNotEmpty())
            assertEquals(3L, logs[0].id)
        }

        @Test
        @DisplayName("组合筛选: taskId + 时间范围 + 关键词")
        fun `selectLogList should handle combined filters`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = 1L,
                taskName = null,
                status = null,
                startTimeFrom = "2026-06-01 00:00:00",
                startTimeTo = "2026-07-31 23:59:59",
                keyword = "summary",
            )
            assertTrue(logs.isNotEmpty())
            logs.forEach {
                assertEquals(1L, it.taskId)
                // 关键词应匹配 prompt 或 response
                val matchPrompt = it.prompt.contains("summary", ignoreCase = true)
                val matchResponse = it.response.contains("summary", ignoreCase = true)
                assertTrue(matchPrompt || matchResponse)
            }
        }

        @Test
        @DisplayName("无匹配结果返回空列表")
        fun `selectLogList should return empty when no match`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = 1L,
                taskName = null,
                status = null,
                startTimeFrom = "2030-01-01 00:00:00",
                startTimeTo = "2030-12-31 23:59:59",
                keyword = null,
            )
            assertTrue(logs.isEmpty())
        }

        @Test
        @DisplayName("关键词无匹配返回空列表")
        fun `selectLogList should return empty when keyword no match`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = null,
                taskName = null,
                status = null,
                startTimeFrom = null,
                startTimeTo = null,
                keyword = "zzzznonexistentkeyword",
            )
            assertTrue(logs.isEmpty())
        }

        @Test
        @DisplayName("结果按 create_time DESC 排序")
        fun `selectLogList should order by create_time desc`() {
            val logs = agentTaskLogMapper.selectLogList(
                taskId = 1L,
                taskName = null,
                status = null,
                startTimeFrom = null,
                startTimeTo = null,
                keyword = null,
            )
            assertTrue(logs.size >= 2)
            for (i in 0 until logs.size - 1) {
                assertTrue(logs[i].createTime >= logs[i + 1].createTime)
            }
        }
    }
}
