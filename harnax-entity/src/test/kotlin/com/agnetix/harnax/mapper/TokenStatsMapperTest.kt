package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.TokenStats
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
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A team's lead has no `agent` row behind it, so its consumption has to be stored without one.
 *
 * The whole point of these cases is the shape the statistics page sees: an agent-less bucket, never a
 * bucket for agent 0. `TokenStatsServiceImpl` groups its per-dimension series by `row["agentId"]` and
 * the chart labels come from the `LEFT JOIN agent`, so a literal 0 arrives as an agent whose id nothing
 * can resolve and whose name is missing — indistinguishable from a deleted agent, and counted as one
 * more agent than the tenant has.
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class TokenStatsMapperTest {

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
    private lateinit var tokenStatsMapper: TokenStatsMapper

    /**
     * The seeded rows sit in 2025/2026 windows, so a one-second window around `ts` holds only what a
     * case in this class inserted.
     */
    private fun around(ts: LocalDateTime): Pair<String, String> {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return ts.minus(1, ChronoUnit.SECONDS).format(format) to ts.plus(1, ChronoUnit.SECONDS).format(format)
    }

    private fun stat(sessionId: String, ts: LocalDateTime) = TokenStats().apply {
        this.sessionId = sessionId
        chatModelId = 1L
        inputToken = 10L
        outputToken = 5L
        totalToken = 15L
        fee = BigDecimal.ZERO
        this.ts = ts
    }

    @Nested
    @DisplayName("Agent-less rows")
    inner class AgentlessRowTests {

        @Test
        @DisplayName("insert - a row without an agent is accepted and kept agent-less")
        fun `insert should store a token stat without an agent`() {
            val ts = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val stats = stat("it-lead-agentless", ts).apply { agentId = null }

            assertEquals(1, tokenStatsMapper.insert(stats))
            assertTrue(stats.id > 0, "the generated key still comes back")

            val (start, end) = around(ts)
            val bucket = tokenStatsMapper.aggregateByAgent(start, end)!!.single()!!
            assertNull(bucket["agentId"], "an agent-less row must not report an agent: $bucket")
            assertNull(bucket["agentName"], "there is no agent row to name: $bucket")
            assertEquals(15L, (bucket["grandTotalToken"] as Number).toLong(), "its tokens still count: $bucket")
        }

        @Test
        @DisplayName("getOverallStats - consumption with no agent does not add an agent")
        fun `getOverallStats should not count an agent-less row as an agent`() {
            val ts = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            assertEquals(1, tokenStatsMapper.insert(stat("it-lead-overall", ts).apply { agentId = null }))

            val (start, end) = around(ts)
            val overall = tokenStatsMapper.getOverallStats(start, end)!!
            assertEquals(0L, (overall["agentCount"] as Number).toLong(), "no agent row backs this consumption: $overall")
            assertEquals(1L, (overall["sessionCount"] as Number).toLong())
            assertEquals(15L, (overall["grandTotalToken"] as Number).toLong())
        }

        @Test
        @DisplayName("aggregateByAgent - a row that has an agent still reports it")
        fun `aggregateByAgent should keep reporting the agent it has`() {
            val ts = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            // Only widening the column may not cost a row its agent, so this is the same insert with an
            // id on it: the bucket has to come back keyed by that id.
            assertEquals(1, tokenStatsMapper.insert(stat("it-agent-kept", ts).apply { agentId = 7L }))

            val (start, end) = around(ts)
            val bucket = tokenStatsMapper.aggregateByAgent(start, end)!!.single()!!
            assertEquals(7L, (bucket["agentId"] as Number).toLong(), "an id that is there still reports: $bucket")
            assertEquals(15L, (bucket["grandTotalToken"] as Number).toLong())
        }
    }
}
