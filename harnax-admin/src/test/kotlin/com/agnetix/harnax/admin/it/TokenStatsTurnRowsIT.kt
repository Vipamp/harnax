package com.agnetix.harnax.admin.it

import com.agnetix.harnax.entity.TokenStats
import com.agnetix.harnax.mapper.TokenStatsMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * What one turn leaves in `token_stats` (AGENT-28/29).
 *
 * The recorder writes one row per model call, which makes the table the ledger of a turn: its rows are
 * its calls, and each row's `ts` is that call's own second. Neither fact is reachable through the
 * endpoints — a time series reports one sum per bucket, so a turn whose three calls collapsed into one
 * row, or whose rows all carried the first call's stamp, reads exactly like a healthy one as long as the
 * totals add up. This class counts the rows and reads the stamps back from the real database.
 *
 * Rows go in through `TokenStatsMapper.insert`, the statement the runtime's adaptor calls, with the
 * columns that adaptor fills. The window and the tenant belong to this class alone: the shared container
 * keeps every row, and the sibling classes assert over tenant 1 and over March 2020.
 */
class TokenStatsTurnRowsIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var tokenStatsMapper: TokenStatsMapper

    private val tenantId = 931_931L
    private val agentId = 931_932L
    private val modelId = 931_933L
    private val sessionId = "token-stats-turn-rows"

    /** One turn: three model calls, each answering with its own usage. */
    private val calls = listOf(
        Call(LocalDateTime.of(2020, 4, 11, 9, 0, 5), 100L, 50L),
        // Seconds apart on purpose — `ts` is a DATETIME, so two calls inside one second are one moment here.
        Call(LocalDateTime.of(2020, 4, 11, 9, 0, 8), 200L, 70L),
        Call(LocalDateTime.of(2020, 4, 11, 9, 0, 12), 30L, 12L),
    )

    private val window = arrayOf("2020-04-11 00:00:00", "2020-04-11 23:59:59")

    private val turnTotal = calls.sumOf { it.input + it.output }

    @BeforeEach
    fun writeTheTurn() {
        clearRows()
        calls.forEach { writeCall(it) }
    }

    @AfterEach
    fun clearRows() {
        jdbc.update("DELETE FROM token_stats WHERE session_id = ?", sessionId)
    }

    private fun writeCall(call: Call) {
        val row = TokenStats()
        row.agentId = agentId
        row.tenantId = tenantId
        row.sessionId = sessionId
        row.chatModelId = modelId
        row.inputToken = call.input
        row.outputToken = call.output
        row.totalToken = call.input + call.output
        row.fee = BigDecimal.ZERO
        row.ts = call.at
        assertEquals(1, tokenStatsMapper.insert(row), "one model call writes one row")
    }

    @Test
    @DisplayName("a turn that called the model three times leaves three rows")
    fun oneRowPerModelCall() {
        val rows = jdbc.queryForList(
            "SELECT input_token, output_token, total_token FROM token_stats WHERE session_id = ? ORDER BY ts",
            sessionId,
        )

        assertEquals(calls.size, rows.size, "the row count is the call count — a turn that collapsed still sums right")
        assertEquals(
            calls.map { listOf(it.input, it.output, it.input + it.output) },
            rows.map {
                listOf(
                    (it["input_token"] as Number).toLong(),
                    (it["output_token"] as Number).toLong(),
                    (it["total_token"] as Number).toLong(),
                )
            },
            "every call of the turn has its own row and its own numbers",
        )
    }

    @Test
    @DisplayName("each row is stamped with its own call, not with the first one")
    fun everyRowCarriesItsOwnMoment() {
        val stamps = jdbc.queryForList(
            "SELECT ts FROM token_stats WHERE session_id = ? ORDER BY ts",
            LocalDateTime::class.java,
            sessionId,
        )

        assertEquals(calls.map { it.at }, stamps, "a row that inherited another call's moment bills its tokens to the wrong second")
        assertEquals(
            stamps.size,
            stamps.distinct().size,
            "one stamp shared by the whole turn is what a frozen seed looks like from the table",
        )
    }

    @Test
    @DisplayName("the rows of a turn add up to that turn on the read side")
    fun perCallRowsStillSumToTheTurn() {
        val overall = tokenStatsMapper.getOverallStats(window[0], window[1], tenantId)!!
        assertEquals(turnTotal, (overall["grandTotalToken"] as Number).toLong())
        assertEquals(calls.sumOf { it.input }, (overall["totalInputToken"] as Number).toLong())
        assertEquals(calls.sumOf { it.output }, (overall["totalOutputToken"] as Number).toLong())
        assertEquals(1L, (overall["sessionCount"] as Number).toLong(), "three rows, one turn")
        assertEquals(1L, (overall["agentCount"] as Number).toLong())
        assertEquals(1L, (overall["modelCount"] as Number).toLong())

        // The three calls fall in one hour, so the hourly series has to fold them into one bucket rather
        // than report three — this is the number the page shows next to the row count above.
        val buckets = tokenStatsMapper.getSessionTimeSeriesByHour(window[0], window[1], tenantId)!!
            .filterNotNull()
            .filter { it["sessionId"] == sessionId }
        assertEquals(1, buckets.size, "one hour, one bucket")
        assertEquals(turnTotal, (buckets.single()["grandTotalToken"] as Number).toLong())
        assertEquals(LocalDateTime.of(2020, 4, 11, 9, 0, 0), buckets.single()["timePoint"])
    }

    private class Call(val at: LocalDateTime, val input: Long, val output: Long)
}
