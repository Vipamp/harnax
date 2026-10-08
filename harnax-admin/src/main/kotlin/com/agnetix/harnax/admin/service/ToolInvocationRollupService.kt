package com.agnetix.harnax.admin.service

import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import com.agnetix.harnax.mapper.ToolInvocationStatsMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Turns `tool_invocation_log` into `tool_invocation_stats` one hour at a time, then releases the detail rows
 * the aggregate has already taken over.
 *
 * Runs hourly so the aggregate lags the details by less than one period — the hour in progress is folded on
 * every run, so the metrics page reads the current hour rather than the last closed one — and it recomputes a
 * whole hour instead of merging deltas because that is what makes the run idempotent: two instances firing
 * at 05:00 write the same numbers, so this needs no distributed lock and the repository has no lock table to
 * buy one with.
 */
@Service
class ToolInvocationRollupService(
    private val toolInvocationLogMapper: ToolInvocationLogMapper,
    private val toolInvocationStatsMapper: ToolInvocationStatsMapper,
    @Value("\${harnax.metrics.retention-days:90}") retentionDays: Long,
    @Value("\${harnax.metrics.rollup-enabled:true}") private val rollupEnabled: Boolean,
) {

    private val log = LoggerFactory.getLogger(ToolInvocationRollupService::class.java)

    /**
     * The retention window in days, clamped into [MIN_RETENTION_DAYS]..[MAX_RETENTION_DAYS] here rather than
     * refused at startup, for the same two reasons the writer gives on its own knobs: refusing to start is the
     * wrong trade for a tuning value this service reads once, and an out-of-band window fails silently rather
     * than loudly — with `retention-days: 0` the cutoff is the running instant, so the next :05 releases every
     * detail row whose hour has been folded, which is every row in the table, while the aggregate keeps
     * answering by tool and the agent and session dimensions quietly go empty. A window of zero is the one
     * misconfiguration here that destroys data rather than merely stopping the count.
     *
     * The ceiling is not a column width but a horizon: the aggregate is permanent and the detail rows only
     * extend the answerable window by tool, so ten years of single-call rows is already past anything a reader
     * can ask, and keeping more would only grow the table the sweep exists to bound.
     */
    private val retentionWindowDays: Long = clampToWindow(retentionDays)

    /** Hourly at :05 so the run does not collide with anything that writes the top of the hour. */
    @Scheduled(cron = "0 5 * * * ?")
    fun rollUpHourly() {
        if (!rollupEnabled) return
        try {
            rollUp()
        } catch (e: Exception) {
            // A scheduler that throws stops firing for the rest of the process's life; a logged failure
            // retries on the next period, and the hour it missed is still in the pending set then — nothing
            // wrote an aggregate row for it, so the pending query still names it.
            log.error("Tool invocation rollup failed: {}", e.message, e)
        }
    }

    /**
     * Roll every hour the detail table says it owes, then delete what the aggregate has taken over; returns
     * the number of hours recomputed. The public seam the integration test drives instead of a cron.
     *
     * `upsertHour`'s own return value is deliberately not accumulated here: MySQL counts an updated row as 2
     * and an unchanged one as 0, so it is not a row count and reads as one in a log line.
     *
     * The bound of the catch-up is the pending set plus the forced two-hour window: a detail row arriving for
     * an older hour that was already folded once does not re-enter the pending set, and the sweep is allowed
     * to release it on the window. That costs one late row of one old hour, and only to a clock skewed back
     * further than an hour — re-opening every already-folded hour to chase it would re-fold the whole
     * retention window every run to guard a case the writers do not produce.
     */
    fun rollUp(): Int {
        val currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        // The current hour is folded whether or not it is pending: it is still being written, and a fold that
        // waited for the hour to close would leave the page an hour behind on every read. The previous hour is
        // folded for the mirror reason at the other end — the sweep fires at :05, so a detail row that arrives
        // between an hour's last fold and its close is already covered by an aggregate row and never re-enters
        // the pending set; without it the closing slice of every hour is folded never, and deleteRolledOut
        // releases those rows anyway.
        val hours = (
            toolInvocationLogMapper.selectUnrolledHours(UNROLLED_FLOOR) +
                currentHour.minusHours(1).format(TIMESTAMP) + currentHour.format(TIMESTAMP)
            ).distinct()

        var rolled = 0
        for (statHour in hours.sorted()) {
            if (LocalDateTime.parse(statHour, TIMESTAMP).isAfter(currentHour)) continue
            toolInvocationStatsMapper.upsertHour(statHour)
            rolled++
        }

        val before = LocalDateTime.now().minusDays(retentionWindowDays).format(TIMESTAMP)
        val deleted = toolInvocationLogMapper.deleteRolledOut(before)
        log.info("Tool invocation rollup: {} hour(s) recomputed, {} detail row(s) older than {} released", rolled, deleted, before)
        return rolled
    }

    /**
     * Move [retentionDays] into the legal window and log the move: a clamp that said nothing would leave the
     * operator reading an env value that is not the window in effect, which is the same blindness the
     * out-of-band value caused, only quieter.
     */
    private fun clampToWindow(retentionDays: Long): Long {
        val clamped = retentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
        if (clamped != retentionDays) {
            log.warn(
                "harnax.metrics.retention-days is set to {}, outside the legal band {}..{}; using {} - detail rows older than that are still released only once their hour has been folded",
                retentionDays,
                MIN_RETENTION_DAYS,
                MAX_RETENTION_DAYS,
                clamped,
            )
        }
        return clamped
    }

    companion object {
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** A window of 0 would make the sweep's cutoff the running instant and release the whole table. */
        private const val MIN_RETENTION_DAYS = 1L

        /** Ten years of detail rows: beyond that the permanent aggregate is the only honest answer anyway. */
        private const val MAX_RETENTION_DAYS = 3650L

        /**
         * Unbounded on purpose: the detail table is already bounded by the retention window, so scanning all
         * of it is cheap, and any floor here would let an hour that never got rolled slip out of the pending
         * set and stay unrolled forever — `deleteRolledOut` refuses to release such a row.
         */
        private const val UNROLLED_FLOOR = "1970-01-01 00:00:00"
    }
}
