package com.agnetix.harnax.mapper

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * The hourly fold of `tool_invocation_log` and the three reads the metrics page answers from it.
 *
 * The write is one statement, an aggregate recompute rather than an increment: the whole hour is counted from
 * the detail rows and every column is overwritten. That is what makes the rollup safe to run twice, safe
 * to run on two replicas at once, and able to catch up on an hour it missed — none of which a
 * `calls = calls + n` increment would give.
 *
 * Catching up has a bound, though: the recompute is only as correct as the detail rows still there to be
 * counted, so it must never run for an hour whose details were already pruned, or it overwrites that hour's
 * true totals with a partial count. `ToolInvocationLogMapper.deleteRolledOut` gates on this table for
 * exactly that reason.
 *
 * That statement is deliberately not tenant-scoped, unlike every read in `ToolInvocationLogMapper`: the
 * rollup is a server-wide maintenance job that writes one row per tenant, and its own tenant predicate
 * would fold only the workspace that happens to run the job.
 *
 * The three reads share one contract: each names a tenant, because there is no value that means "every
 * workspace", and each bounds the window with two inclusive hour starts, `yyyy-MM-dd HH:mm:ss`, against
 * `stat_hour`. The detail table's reads in `ToolInvocationLogMapper` take that same lower bound and one hour
 * past the upper one, because an aggregate hour row covers sixty minutes; the service derives both forms from
 * one window.
 *
 * SQL lives in `resources/mapper/ToolInvocationStatsMapper.xml`.
 */
@Mapper
interface ToolInvocationStatsMapper {

    /**
     * Recompute one hour in full.
     *
     * @param statHour Hour to fold, `yyyy-MM-dd HH:mm:ss` with zero minutes and seconds
     * @return Rows touched; MySQL counts an updated row as 2 and an unchanged one as 0, so the number is
     * not a row count and callers must not read it as one
     */
    fun upsertHour(
        @Param("statHour") statHour: String,
    ): Int

    /**
     * One row per subject over an hour range, summed across hours, with the six duration buckets so the P95 can
     * be answered without touching the detail table. Aliases are the wire contract: the service reads the map
     * by these keys.
     *
     * @param dimension What a subject is: `tool` groups by kind, subject id and tool name, `mcp` and `cli` by
     * kind and subject id alone, so one server's or one package's several names fold into one row and their
     * `toolName` comes back empty. The caller has resolved this, so it is never null and never `agent` or
     * `session` — those two dimensions read the detail table.
     */
    fun selectSubjectTotals(
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("tenantId") tenantId: Long,
        @Param("kind") kind: String?,
        @Param("dimension") dimension: String,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * The same totals for the whole window as one row, with no grouping at all.
     *
     * This is what the four cards answer from, including under `groupBy=agent|session`, where the rows come
     * from the detail table and have no buckets — a card must not borrow a maximum and call it a percentile.
     * An aggregate without `GROUP BY` always returns exactly one row, and COALESCE keeps its counts at 0.
     */
    fun selectWindowTotals(
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("tenantId") tenantId: Long,
        @Param("kind") kind: String?,
    ): MutableMap<String?, Any?>?

    /**
     * The same totals per bucket, for the trend line.
     *
     * `granularity` is one of `hour`, `day`, `week` or `month`, and each floors `stat_hour` to its own origin.
     * The service walks the window with the same four alignments: a bucket expression and a walk that disagree
     * make every zero-filled point look up under a key the query never produces, and the line answers zeros.
     */
    fun selectTimeSeries(
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("tenantId") tenantId: Long,
        @Param("kind") kind: String?,
        @Param("subjectId") subjectId: Long?,
        @Param("granularity") granularity: String,
    ): MutableList<MutableMap<String?, Any?>?>?
}
