package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolInvocationRow
import com.agnetix.harnax.admin.dto.ToolMetricsPoint
import com.agnetix.harnax.admin.dto.ToolMetricsRow
import com.agnetix.harnax.admin.dto.ToolMetricsSummaryResponse
import com.agnetix.harnax.admin.dto.ToolMetricsTimeSeriesResponse
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.service.InvocationQuery
import com.agnetix.harnax.admin.service.ToolMetricsService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.TenantResolver
import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import com.agnetix.harnax.mapper.ToolInvocationStatsMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * The three reads behind `/api/admin/tool-metrics`.
 *
 * Two tables answer, and which one depends on the dimension the page asked for: the daily aggregate carries
 * the tool dimension and the six duration buckets, while an agent or a session only exists in the detail
 * rows. The four cards never follow the rows, because on the detail path the rows carry no buckets and a
 * percentile would have to be borrowed from a maximum — a number that reads as a distribution and is one
 * observation.
 *
 * Nothing here resolves a tenant from a request value. [JwtUtil] says whose workspace this call is, and a
 * caller that could name a tenant in a URL could name any other one.
 */
@Service
class ToolMetricsServiceImpl(
    private val toolInvocationStatsMapper: ToolInvocationStatsMapper,
    private val toolInvocationLogMapper: ToolInvocationLogMapper,
    private val jwtUtil: JwtUtil,
) : ToolMetricsService {

    private val log = LoggerFactory.getLogger(ToolMetricsServiceImpl::class.java)

    override fun getSummary(
        start: String?,
        end: String?,
        kind: String?,
        groupBy: String,
    ): ToolMetricsSummaryResponse {
        val window = window(start, end)
        val dimension = dimension(groupBy)
        val tenantId = currentTenantId()
        // One ungrouped aggregate row, whatever the rows below come from: the cards and the window P95 are
        // properties of the window, not of the dimension the page happens to be grouped by.
        val totals = toolInvocationStatsMapper.selectWindowTotals(window.fromDate, window.toDate, tenantId, kind)
        val totalCalls = totals.longOf("calls")
        val totalSuccesses = totals.longOf("successes")
        // Added up from the three failure columns rather than taken as calls minus successes, so that the sum
        // only answers the whole window when the four terminal outcomes really partition it.
        val failingCalls = totals.longOf("errors") + totals.longOf("denials") + totals.longOf("interruptions")
        val (windowOperator, windowMs) = p95(totals, totalCalls)
        val rows = if (dimension == DIM_TOOL) toolRows(window, tenantId, kind) else detailRows(window, tenantId, kind, dimension)
        return ToolMetricsSummaryResponse(
            from = window.fromDate,
            to = window.toDate,
            groupBy = dimension,
            totalCalls = totalCalls,
            totalSuccesses = totalSuccesses,
            successRate = rateOf(totalSuccesses, totalCalls),
            failingCalls = failingCalls,
            p95Operator = windowOperator,
            p95Ms = windowMs,
            rows = rows,
        )
    }

    override fun getTimeSeries(
        start: String?,
        end: String?,
        kind: String?,
        subjectId: Long?,
        granularity: String,
    ): ToolMetricsTimeSeriesResponse {
        val window = window(start, end)
        val bucket = granularityOf(granularity)
        val rows = toolInvocationStatsMapper.selectTimeSeries(window.fromDate, window.toDate, currentTenantId(), kind, subjectId, bucket).orEmpty()

        // The query only answers buckets that have rows, so the series has to be assembled from the two sets
        // the page needs intact: every bucket in the window, and every subject the window named at all.
        val subjects = LinkedHashMap<String, Map<String?, Any?>>()
        val rowsByBucket = mutableMapOf<Pair<String, String>, Map<String?, Any?>>()
        for (row in rows) {
            val timePoint = row?.stampOf("timePoint") ?: continue
            val subject = subjectToken(row)
            subjects.putIfAbsent(subject, row)
            rowsByBucket[timePoint to subject] = row
        }

        val points = mutableListOf<ToolMetricsPoint>()
        for (timePoint in timePoints(window, bucket)) {
            val stamp = timePoint.format(TIMESTAMP_FORMATTER)
            for ((subject, sample) in subjects) {
                // A bucket with no row for this subject is a quiet day, not a missing point: the line the page
                // draws has to keep stepping over it, so the counts come from an empty row and stay 0.
                val row = rowsByBucket[stamp to subject]
                val calls = row.longOf("calls")
                points.add(
                    ToolMetricsPoint(
                        timePoint = stamp,
                        kind = sample.stringOf("kind"),
                        dimensionId = sample.subjectIdOf("dimensionId"),
                        dimensionName = sample.stringOf("dimensionName"),
                        calls = calls,
                        successes = row.longOf("successes"),
                        errors = row.longOf("errors"),
                        denials = row.longOf("denials"),
                        interruptions = row.longOf("interruptions"),
                        avgDurationMs = average(row.longOf("sumDurationMs"), calls),
                    ),
                )
            }
        }
        return ToolMetricsTimeSeriesResponse(from = window.fromDate, to = window.toDate, granularity = bucket, points = points)
    }

    override fun getInvocations(query: InvocationQuery): Page<ToolInvocationRow> {
        val window = window(query.start, query.end)
        PageHelper.startPage<Map<String?, Any?>>(query.pageNum.coerceAtLeast(1), query.pageSize.coerceIn(1, MAX_PAGE_SIZE))
        // Nothing may be queried between startPage and this call: PageHelper parks its request on a thread
        // local that the next statement consumes, so one inserted query would take the paging and leave this
        // one answering every row while the page reports a single slice of it.
        return Page.fromPageInfo(
            toolInvocationLogMapper.selectInvocationPage(
                window.fromTs,
                window.toTs,
                currentTenantId(),
                query.kind,
                query.toolName?.trim()?.takeIf { it.isNotEmpty() },
                query.mcpId,
                query.cliId,
                query.agentId,
                query.sessionId?.trim()?.takeIf { it.isNotEmpty() },
                query.outcome,
            ) ?: emptyList(),
        ).mapRecords { it.toRow() }
    }

    /**
     * Rows for the tool dimension: one per subject over the whole window, straight from the aggregate.
     *
     * The key is the tool name, because that is what the page lists, and an id only travels with a subject
     * the drill-down can point at.
     */
    private fun toolRows(
        window: Window,
        tenantId: Long,
        kind: String?,
    ): List<ToolMetricsRow> {
        val rows = mutableListOf<ToolMetricsRow>()
        for (row in toolInvocationStatsMapper.selectSubjectTotals(window.fromDate, window.toDate, tenantId, kind).orEmpty()) {
            row ?: continue
            val calls = row.longOf("calls")
            val successes = row.longOf("successes")
            val (operator, ms) = p95(row, calls)
            rows.add(
                ToolMetricsRow(
                    kind = row.stringOf("kind"),
                    subjectKey = row.stringOf("toolName"),
                    subjectId = row.subjectIdOf("subjectId"),
                    toolName = row.stringOf("toolName"),
                    calls = calls,
                    successes = successes,
                    errors = row.longOf("errors"),
                    denials = row.longOf("denials"),
                    interruptions = row.longOf("interruptions"),
                    successRate = rateOf(successes, calls),
                    avgDurationMs = average(row.longOf("sumDurationMs"), calls),
                    p95Operator = operator,
                    p95Ms = ms,
                    lastSeenAt = row.stampOf("lastSeenAt"),
                ),
            )
        }
        return rows
    }

    /**
     * Rows for the two dimensions the aggregate does not carry, counted from the detail rows.
     *
     * A row here spans every tool the subject ran, so the tool name and the origin stay empty; the key is the
     * grouped column itself. The P95 has no bucket to answer from on this path, so it reports the longest
     * call actually measured, and the window's percentile beside it comes from the aggregate instead.
     */
    private fun detailRows(
        window: Window,
        tenantId: Long,
        kind: String?,
        dimension: String,
    ): List<ToolMetricsRow> {
        val rows = mutableListOf<ToolMetricsRow>()
        for (row in toolInvocationLogMapper.selectSubjectTotalsFromDetail(window.fromTs, window.toTs, tenantId, kind, dimension).orEmpty()) {
            row ?: continue
            val calls = row.longOf("calls")
            val successes = row.longOf("successes")
            rows.add(
                ToolMetricsRow(
                    subjectKey = row.stringOf("subjectKey"),
                    subjectId = row.subjectIdOf("subjectId"),
                    calls = calls,
                    successes = successes,
                    errors = row.longOf("errors"),
                    denials = row.longOf("denials"),
                    interruptions = row.longOf("interruptions"),
                    successRate = rateOf(successes, calls),
                    avgDurationMs = average(row.longOf("sumDurationMs"), calls),
                    p95Operator = "<=",
                    p95Ms = row.longOf("maxDurationMs"),
                    lastSeenAt = row.stampOf("lastSeenAt"),
                ),
            )
        }
        return rows
    }

    /** Which bucket the 95th percentile lands in, as the operator and bound the page renders. */
    private fun p95(
        row: Map<String?, Any?>?,
        calls: Long,
    ): Pair<String, Long> {
        if (calls <= 0L) return "<=" to 0L
        val needed = ceil(calls * 0.95).toLong()
        var seen = 0L
        for ((key, bound) in BUCKETS) {
            seen += (row?.get(key) as? Number)?.toLong() ?: 0L
            if (seen >= needed) return (if (key == "gt30s") ">" else "<=") to bound
        }
        return ">" to 30_000L
    }

    private fun dimension(
        groupBy: String,
    ): String = if (groupBy == DIM_AGENT || groupBy == DIM_SESSION) groupBy else DIM_TOOL

    private fun granularityOf(
        granularity: String,
    ): String = if (granularity == GRANULARITY_WEEK || granularity == GRANULARITY_MONTH) granularity else GRANULARITY_DAY

    /** Bucket starts the window covers, oldest first, aligned exactly as the SQL's bucket expression is. */
    private fun timePoints(
        window: Window,
        granularity: String,
    ): List<LocalDateTime> {
        var cursor = align(window.from, granularity)
        val points = mutableListOf<LocalDateTime>()
        while (!cursor.isAfter(window.to)) {
            points.add(cursor)
            cursor = when (granularity) {
                GRANULARITY_WEEK -> cursor.plusWeeks(1)
                GRANULARITY_MONTH -> cursor.plusMonths(1)
                else -> cursor.plusDays(1)
            }
        }
        return points
    }

    /** Monday for week, the 1st for month, 00:00 for day: the same three alignments as selectTimeSeries. */
    private fun align(
        from: LocalDateTime,
        granularity: String,
    ): LocalDateTime = when (granularity) {
        GRANULARITY_WEEK -> from.toLocalDate().minusDays((from.dayOfWeek.value - 1).toLong()).atStartOfDay()
        GRANULARITY_MONTH -> from.toLocalDate().withDayOfMonth(1).atStartOfDay()
        else -> from.toLocalDate().atStartOfDay()
    }

    /**
     * The requested day range, clamped to what these tables can answer.
     *
     * Both bounds are inclusive days. A missing `end` means today; a missing `start` means the default span
     * ending at `end`, so the two bounds are each other's fallback rather than two separate defaults — a page
     * that sends only `end=2026-05-01` gets the 30 days before it, which is what the same request with no
     * window answers today. `start` after `end` collapses onto `end`, and a span wider than
     * [MAX_WINDOW_DAYS] pushes `start` forward rather than rejecting the request.
     *
     * A value that does not parse counts as absent: the page can only send what its picker produced, so a
     * malformed date is a bug on that side, and answering with the default range beats failing the whole card
     * row. Every clamp is logged with the values it resolved to, because the response always echoes the range
     * that was actually answered.
     */
    private fun window(
        start: String?,
        end: String?,
    ): Window {
        val today = LocalDate.now()
        val requestedEnd = parseDate(end)
        if (end != null && requestedEnd == null) {
            log.info("Metrics window end `$end` is not a yyyy-MM-dd day, using today {}", today)
        }
        var to = requestedEnd ?: today
        if (requestedEnd != null && requestedEnd.isAfter(today)) {
            log.info("Metrics window end {} is in the future, clamped to {}", requestedEnd, today)
            to = today
        }
        val requestedStart = parseDate(start)
        if (start != null && requestedStart == null) {
            log.info("Metrics window start `$start` is not a yyyy-MM-dd day, using {} days before {}", DEFAULT_WINDOW_DAYS, to)
        }
        var from = requestedStart ?: to.minusDays((DEFAULT_WINDOW_DAYS - 1).toLong())
        if (from.isAfter(to)) {
            log.info("Metrics window start {} is after end {}, clamped to the end day", from, to)
            from = to
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_WINDOW_DAYS) {
            val pushed = to.minusDays((MAX_WINDOW_DAYS - 1).toLong())
            log.info("Metrics window {}..{} spans more than {} days, start pushed to {}", from, to, MAX_WINDOW_DAYS, pushed)
            from = pushed
        }
        return Window(from.atStartOfDay(), to.plusDays(1).atStartOfDay().minusNanos(1_000_000L))
    }

    /** A request value as a day, with blank and unparseable both answering null rather than throwing. */
    private fun parseDate(
        value: String?,
    ): LocalDate? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { LocalDate.parse(it, DATE_FORMATTER) }.getOrNull() }

    /**
     * The subject a series row belongs to.
     *
     * Origin, id and name together, with the raw id: `0` is what the aggregate writes for a builtin call, and
     * collapsing it to absent here would fold two different subjects into one series.
     */
    private fun subjectToken(row: Map<String?, Any?>?): String = "${row.stringOf("kind")}|${row.longOf("dimensionId")}|${row.stringOf("dimensionName")}"

    /** A subject id is only worth sending when something can be looked up by it. */
    private fun Map<String?, Any?>?.subjectIdOf(key: String): Long? = (this?.get(key) as? Number)?.toLong()?.takeIf { it != 0L }

    private fun Map<String?, Any?>?.longOf(key: String): Long = (this?.get(key) as? Number)?.toLong() ?: 0L

    private fun Map<String?, Any?>?.stringOf(key: String): String = this?.get(key) as? String ?: ""

    private fun Map<String?, Any?>?.textOf(key: String): String? = this?.get(key) as? String

    /**
     * A datetime column as the stamp the page reads.
     *
     * Formatted here rather than handed over typed: Jackson writes `java.time` in ISO form and ignores
     * `spring.jackson.date-format`, and the trend's `timePoint` in this very response family is already a
     * formatted label. Fractional seconds go with the format — the detail column is `datetime(3)` while the
     * fixtures, the aggregate and the page's own column all resolve to a second.
     */
    private fun Map<String?, Any?>?.stampOf(key: String): String? = (this?.get(key) as? LocalDateTime)?.format(TIMESTAMP_FORMATTER)

    /**
     * Successes over calls as a fraction.
     *
     * No rounding here: the page owns the percentage format, and an empty window answers 0 rather than NaN.
     */
    private fun rateOf(
        successes: Long,
        calls: Long,
    ): Double = if (calls == 0L) 0.0 else successes.toDouble() / calls.toDouble()

    /** The integer floor of the mean, which is all the aggregate can support: a sum and a count. */
    private fun average(
        sum: Long,
        calls: Long,
    ): Long = if (calls == 0L) 0L else sum / calls

    private fun Map<String?, Any?>?.toRow(): ToolInvocationRow = ToolInvocationRow(
        id = longOf("id"),
        kind = stringOf("kind"),
        toolName = stringOf("toolName"),
        agentId = (this?.get("agentId") as? Number)?.toLong(),
        sessionId = stringOf("sessionId"),
        userId = (this?.get("userId") as? Number)?.toLong(),
        mcpId = (this?.get("mcpId") as? Number)?.toLong(),
        cliId = (this?.get("cliId") as? Number)?.toLong(),
        outcome = stringOf("outcome"),
        errorMessage = textOf("errorMessage"),
        argsJson = textOf("argsJson"),
        resultExcerpt = textOf("resultExcerpt"),
        durationMs = longOf("durationMs"),
        startTime = stampOf("startTime"),
        ts = stampOf("ts"),
    )

    private fun currentTenantId(): Long = TenantResolver.resolve(jwtUtil)

    /**
     * A clamped day range as the two precisions the two tables compare against.
     *
     * `to` is the last millisecond of the end day rather than its start: both detail queries bound the upper
     * side with `<=`, so a start-of-day bound would answer a range one day short of what the page asked for,
     * and the column is `datetime(3)`, so `.999` is the widest bound that stays inside the day. The aggregate
     * reads a `date` column and takes the day strings, where the same instant formats back to the end day.
     */
    private class Window(
        val from: LocalDateTime,
        val to: LocalDateTime,
    ) {
        val fromDate: String get() = from.format(DATE_FORMATTER)
        val toDate: String get() = to.format(DATE_FORMATTER)
        val fromTs: String get() = from.format(TIMESTAMP_FORMATTER)
        val toTs: String get() = to.format(BOUND_FORMATTER)
    }

    companion object {
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** The bound formatter carries the millisecond the upper bound needs; row stamps stay second-precise. */
        private val BOUND_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        const val DEFAULT_WINDOW_DAYS = 30
        const val MAX_WINDOW_DAYS = 365
        const val MAX_PAGE_SIZE = 200

        const val DIM_TOOL = "tool"
        const val DIM_AGENT = "agent"
        const val DIM_SESSION = "session"

        const val GRANULARITY_DAY = "day"
        const val GRANULARITY_WEEK = "week"
        const val GRANULARITY_MONTH = "month"

        /** Bucket upper bounds in ms, in the order the six columns accumulate; the last one is open-ended. */
        private val BUCKETS: List<Pair<String, Long>> =
            listOf(
                "le100ms" to 100L,
                "le500ms" to 500L,
                "le2s" to 2_000L,
                "le10s" to 10_000L,
                "le30s" to 30_000L,
                "gt30s" to 30_000L,
            )
    }
}
