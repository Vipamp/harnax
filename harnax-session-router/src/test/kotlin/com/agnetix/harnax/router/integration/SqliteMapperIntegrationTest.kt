package com.agnetix.harnax.router.integration

import com.agnetix.harnax.router.config.SqliteInitConfig
import com.agnetix.harnax.router.dto.ApiCallLogQuery
import com.agnetix.harnax.router.entity.ApiCallLog
import com.agnetix.harnax.router.mapper.ApiCallLogMapper
import com.zaxxer.hikari.HikariDataSource
import org.apache.ibatis.mapping.Environment
import org.apache.ibatis.session.Configuration
import org.apache.ibatis.session.SqlSession
import org.apache.ibatis.session.SqlSessionFactoryBuilder
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * SQLite 集成测试 - 验证 MyBatis Mapper 在 SQLite 上的完整工作流
 */
class SqliteMapperIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dataSource: HikariDataSource
    private lateinit var sqlSession: SqlSession
    private lateinit var mapper: ApiCallLogMapper

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("test-mapper.db").toString()
        dataSource = HikariDataSource().apply {
            jdbcUrl = "jdbc:sqlite:$dbPath"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
        }

        // 初始化表结构
        val initConfig = SqliteInitConfig(dataSource)
        initConfig.initDatabase()

        // 配置 MyBatis
        val configuration = Configuration().apply {
            isMapUnderscoreToCamelCase = true
            environment = Environment("test", JdbcTransactionFactory(), dataSource)
            addMapper(ApiCallLogMapper::class.java)
        }

        // 加载 XML mapper
        val resource = this::class.java.classLoader.getResourceAsStream("mapper/ApiCallLogMapper.xml")
        if (resource != null) {
            configuration.addMapper(ApiCallLogMapper::class.java)
        }

        val sqlSessionFactory = SqlSessionFactoryBuilder().build(configuration)
        sqlSession = sqlSessionFactory.openSession(true)
        mapper = sqlSession.getMapper(ApiCallLogMapper::class.java)
    }

    @AfterEach
    fun tearDown() {
        sqlSession.close()
        dataSource.close()
    }

    private fun createLogEntry(
        id: Int = 1,
        sessionId: String = "sess-$id",
        statusCode: Int = 200,
        durationMs: Long = 100
    ): ApiCallLog = ApiCallLog().apply {
        callerId = "caller-$id"
        callerType = "EXTERNAL_API"
        tenantId = 1L
        this.sessionId = sessionId
        agentId = 10L
        agentName = "test-agent"
        modelId = 3L
        modelName = "gpt-4"
        endpoint = "/api/router/agent/chat"
        method = "POST"
        requestType = "CHAT"
        this.statusCode = statusCode
        success = if (statusCode == 200) 1 else 0
        errorMessage = if (statusCode != 200) "error" else null
        startTime = LocalDateTime.now()
        endTime = LocalDateTime.now().plusNanos(durationMs * 1_000_000)
        this.durationMs = durationMs
        instanceId = "inst-1"
        requestId = "req-$id"
    }

    @Test
    fun `insert single log entry`() {
        val log = createLogEntry()
        val affected = mapper.insert(log)

        assertEquals(1, affected)
        assertNotNull(log.id)
    }

    @Test
    fun `batch insert multiple entries`() {
        val logs = (1..10).map { createLogEntry(it) }
        val affected = mapper.batchInsert(logs)

        assertEquals(10, affected)
    }

    @Test
    fun `query all entries`() {
        val logs = (1..5).map { createLogEntry(it) }
        mapper.batchInsert(logs)

        val query = ApiCallLogQuery()
        val results = mapper.query(query)

        assertEquals(5, results.size)
    }

    @Test
    fun `query with sessionId filter`() {
        mapper.batchInsert((1..5).map { createLogEntry(it, sessionId = "sess-filter") })
        mapper.batchInsert((6..10).map { createLogEntry(it, sessionId = "sess-other") })

        val query = ApiCallLogQuery(sessionId = "sess-filter")
        val results = mapper.query(query)

        assertEquals(5, results.size)
        results.forEach { assertEquals("sess-filter", it.sessionId) }
    }

    @Test
    fun `query with statusCode filter`() {
        mapper.batchInsert((1..3).map { createLogEntry(it, statusCode = 200) })
        mapper.batchInsert((4..6).map { createLogEntry(it, statusCode = 500) })

        val query = ApiCallLogQuery(statusCode = 500)
        val results = mapper.query(query)

        assertEquals(3, results.size)
        results.forEach { assertEquals(500, it.statusCode) }
    }

    @Test
    fun `query with instanceId filter`() {
        val logs1 = (1..3).map { createLogEntry(it).apply { instanceId = "inst-1" } }
        val logs2 = (4..6).map { createLogEntry(it).apply { instanceId = "inst-2" } }
        mapper.batchInsert(logs1)
        mapper.batchInsert(logs2)

        val query = ApiCallLogQuery(instanceId = "inst-2")
        val results = mapper.query(query)

        assertEquals(3, results.size)
        results.forEach { assertEquals("inst-2", it.instanceId) }
    }

    @Test
    fun `query with agentName filter`() {
        val logs = (1..5).map { createLogEntry(it).apply { agentName = "agent-filter" } }
        mapper.batchInsert(logs)

        val query = ApiCallLogQuery(agentName = "agent-filter")
        val results = mapper.query(query)

        assertEquals(5, results.size)
    }

    @Test
    fun `query with success filter`() {
        mapper.batchInsert((1..3).map { createLogEntry(it, statusCode = 200) })
        mapper.batchInsert((4..6).map { createLogEntry(it, statusCode = 500) })

        val query = ApiCallLogQuery(success = 0)
        val results = mapper.query(query)

        assertEquals(3, results.size)
        results.forEach { assertEquals(0, it.success) }
    }

    @Test
    fun `query with minDurationMs filter`() {
        mapper.batchInsert((1..3).map { createLogEntry(it, durationMs = 50) })
        mapper.batchInsert((4..6).map { createLogEntry(it, durationMs = 200) })

        val query = ApiCallLogQuery(minDurationMs = 100)
        val results = mapper.query(query)

        assertEquals(3, results.size)
        results.forEach { assertTrue(it.durationMs >= 100) }
    }

    @Test
    fun `count entries`() {
        mapper.batchInsert((1..10).map { createLogEntry(it) })

        val query = ApiCallLogQuery()
        val count = mapper.count(query)

        assertEquals(10, count)
    }

    @Test
    fun `count with filter`() {
        mapper.batchInsert((1..5).map { createLogEntry(it, sessionId = "sess-a") })
        mapper.batchInsert((6..10).map { createLogEntry(it, sessionId = "sess-b") })

        val query = ApiCallLogQuery(sessionId = "sess-a")
        val count = mapper.count(query)

        assertEquals(5, count)
    }

    @Test
    fun `query with pagination`() {
        mapper.batchInsert((1..20).map { createLogEntry(it) })

        val query = ApiCallLogQuery(limit = 5, offset = 0)
        val page1 = mapper.query(query)
        assertEquals(5, page1.size)

        val query2 = ApiCallLogQuery(limit = 5, offset = 5)
        val page2 = mapper.query(query2)
        assertEquals(5, page2.size)

        // 确保分页不重复
        val ids1 = page1.map { it.id }.toSet()
        val ids2 = page2.map { it.id }.toSet()
        assertTrue(ids1.intersect(ids2).isEmpty(), "Pages should not overlap")
    }

    @Test
    fun `query returns results ordered by start_time DESC`() {
        val logs = (1..5).map { i ->
            createLogEntry(i).apply {
                startTime = LocalDateTime.now().minusMinutes(i.toLong())
            }
        }
        mapper.batchInsert(logs)

        val query = ApiCallLogQuery()
        val results = mapper.query(query)

        // 验证按 start_time 降序
        for (i in 0 until results.size - 1) {
            assertTrue(
                results[i].startTime.isAfter(results[i + 1].startTime) ||
                results[i].startTime.isEqual(results[i + 1].startTime),
                "Results should be ordered by start_time DESC"
            )
        }
    }

    @Test
    fun `combined filters work together`() {
        val logs = (1..20).map { i ->
            createLogEntry(i).apply {
                sessionId = if (i <= 10) "sess-a" else "sess-b"
                statusCode = if (i % 2 == 0) 200 else 500
                instanceId = "inst-${i % 3 + 1}"
            }
        }
        mapper.batchInsert(logs)

        val query = ApiCallLogQuery(
            sessionId = "sess-a",
            statusCode = 200
        )
        val results = mapper.query(query)

        // sess-a 有 10 条，其中偶数（statusCode=200）有 5 条
        assertEquals(5, results.size)
        results.forEach {
            assertEquals("sess-a", it.sessionId)
            assertEquals(200, it.statusCode)
        }
    }

    @Test
    fun `empty result returns empty list`() {
        val query = ApiCallLogQuery(sessionId = "non-existent")
        val results = mapper.query(query)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `empty count returns zero`() {
        val query = ApiCallLogQuery(sessionId = "non-existent")
        val count = mapper.count(query)

        assertEquals(0, count)
    }
}
