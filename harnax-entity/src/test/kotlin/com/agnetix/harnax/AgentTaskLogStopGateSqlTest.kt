package com.agnetix.harnax

import com.agnetix.harnax.mapper.AgentTaskLogMapper
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.InputStream

/**
 * Stopping an execution is a write, and until now it was authorised with the *read* rule: the gate
 * joined `agent_task` on `is_public = 1 OR creator = ?`, so any logged-in user could interrupt a run of
 * anybody's public task. The rest of this domain writes owner-only (`updateById`, `deleteById`).
 *
 * The rule lives in SQL, so the assertion has to read SQL. Docker-off machines cannot run the
 * Testcontainers mapper suite, and MyBatis can resolve a statement without a database — the mapper XML is
 * parsed and the bound SQL is inspected here, which is the same text MySQL would get.
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
            "写门禁必须按 creator 过滤，实际 SQL: $sql",
        )
        assertFalse(sql.contains("is_public"), "停止是写操作，public 不构成权限，实际 SQL: $sql")
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

        assertTrue(listSql.contains("is_public = 1 or t.creator = ?"), "读侧仍应公开可见，实际 SQL: $listSql")
        val stopSql = boundSqlOf("selectOwnedById", mapOf("id" to 1L, "currentUsername" to "alice"))
        assertTrue(
            listSql != stopSql,
            "读写两条门禁解析成了同一段 SQL，说明其中一侧的规则被顺手改了",
        )
    }

    /** The un-gated read the batch removed must not come back under the old name either. */
    @Test
    @DisplayName("按读可见性授权写操作的那个入口已经不存在")
    fun `the read-shaped gate is gone from both the xml and the interface`() {
        assertFalse(
            configuration.hasStatement("$NAMESPACE.selectVisibleById", false),
            "selectVisibleById 是按读可见性授权写的语句，不得复活",
        )
        assertTrue(
            AgentTaskLogMapper::class.java.methods.none { it.name == "selectVisibleById" },
            "接口上不得再留有读形状的停止门禁",
        )
    }

    companion object {
        private const val NAMESPACE = "com.agnetix.harnax.mapper.AgentTaskLogMapper"
    }
}
