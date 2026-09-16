package com.agnetix.harnax.scheduler

import com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.InputStream

/**
 * Stopping an execution is a write, and until release 1 it was authorised with the *read* rule: the gate
 * joined `agent_task` on `is_public = 1 OR creator = ?`, so any logged-in user could interrupt a run of
 * anybody's public task. The rest of this domain writes owner-only (`updateById`, `deleteById`).
 *
 * The rule lives in SQL, so the assertion has to read SQL. Docker-off machines cannot run the Testcontainers
 * mapper suite ([com.agnetix.harnax.scheduler.it.AgentTaskMapperSemanticsIT] covers the same gate on a real
 * MySQL), and MyBatis can resolve a statement without a database — the mapper XML is parsed here and the
 * bound SQL is inspected, which is the same text MySQL would get.
 *
 * It moved here with release 2 because this module now owns the three task tables: the XML
 * below is this module's own copy, and the namespace is the interface this module's `@MapperScan` registers.
 * A copy of that file still living in `harnax-entity` would be caught by the [NAMESPACE] lookup — the
 * statements would simply not resolve — rather than by classpath luck.
 */
@DisplayName("执行日志的停止门禁 - 写可见性 SQL")
class AgentTaskLogStopGateSqlTest {

    private val configuration: Configuration = Configuration().apply {
        val resource = PathMatchingResourcePatternResolver()
            .getResource("classpath:mapper/AgentTaskLogMapper.xml")
        resource.inputStream.use { stream: InputStream ->
            XMLMapperBuilder(stream, this, resource.toString(), sqlFragments).parse()
        }
    }

    private fun boundSqlOf(statementSuffix: String, parameters: Map<String, Any?>): String = configuration
        .getMappedStatement("$NAMESPACE.$statementSuffix", false)
        .getBoundSql(parameters)
        .sql
        .normalize()

    private fun String.normalize(): String = replace(Regex("\\s+"), " ").lowercase()

    /**
     * The gate itself: owner only. `is_public` may not appear anywhere in it — the moment it does, every
     * logged-in user can interrupt a stranger's run, which is the bug.
     */
    @Test
    @DisplayName("停止门禁只认 creator，不接受 is_public")
    fun `the stop gate matches the creator and never the public flag`() {
        val sql = boundSqlOf("selectOwnedById", mapOf("id" to 1L, "currentUsername" to "alice"))

        assertTrue(
            sql.contains("t.creator = ?"),
            "the write gate has to filter on creator; bound SQL was: $sql",
        )
        assertFalse(sql.contains("is_public"), "stopping is a write, and public is not a permission; bound SQL was: $sql")
    }

    /**
     * The asymmetry is deliberate and has to stay visible: the list read still lets a caller *see* a
     * public task's executions. Seeing is not stopping. If the two statements ever converge onto one
     * predicate again, either the leak or the blindness comes back, and this pair is what notices.
     */
    @Test
    @DisplayName("列表读仍按可见性过滤：可读不等于可停")
    fun `the list read keeps the public branch that the write gate drops`() {
        val listSql = boundSqlOf(
            "selectLogList",
            mapOf(
                "taskId" to null,
                "taskName" to null,
                "status" to null,
                "startTimeFrom" to null,
                "startTimeTo" to null,
                "keyword" to null,
                "currentUsername" to "alice",
            ),
        )

        assertTrue(listSql.contains("is_public = 1 or t.creator = ?"), "the read side must stay publicly visible; bound SQL was: $listSql")
        val stopSql = boundSqlOf("selectOwnedById", mapOf("id" to 1L, "currentUsername" to "alice"))
        assertTrue(
            listSql != stopSql,
            "the read gate and the write gate resolve to one SQL text, so one of the two rules was changed on the way",
        )
    }

    /** The un-gated read the batch removed must not come back under the old name either. */
    @Test
    @DisplayName("按读可见性授权写操作的那个入口已经不存在")
    fun `the read-shaped gate is gone from both the xml and the interface`() {
        assertFalse(
            configuration.hasStatement("$NAMESPACE.selectVisibleById", false),
            "selectVisibleById authorised a write with the read visibility rule and may not come back",
        )
        assertTrue(
            AgentTaskLogMapper::class.java.methods.none { it.name == "selectVisibleById" },
            "the interface must not keep a read-shaped stop gate either",
        )
    }

    /**
     * The other half of the same hole: `selectByTaskId` handed back every log of a task to anyone who knew
     * the id, with no join to the owning task at all, and the log list replaced it. `selectRunningByTaskId`
     * is a different statement and stays: it is the engine's own liveness probe on a task the caller already
     * holds, not a user-facing read, and no request path reaches it.
     */
    @Test
    @DisplayName("按 task id 无门禁的读取不再存在")
    fun `the unguarded read by task id is gone from both the xml and the interface`() {
        assertFalse(
            configuration.hasStatement("$NAMESPACE.selectByTaskId", false),
            "selectByTaskId read logs with no visibility join and was removed for that reason",
        )
        assertFalse(
            AgentTaskLogMapper::class.java.methods.any { it.name == "selectByTaskId" },
            "the interface must not offer an unguarded read by task id either",
        )
    }

    companion object {
        private const val NAMESPACE = "com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper"
    }
}
