package com.agnetix.harnax.mapper

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * The daily fold of `tool_invocation_log`.
 *
 * One statement, and it is an aggregate recompute rather than an increment: the whole day is counted from
 * the detail rows and every column is overwritten. That is what makes the rollup safe to run twice, safe
 * to run on two replicas at once, and able to catch up on a day it missed — none of which a
 * `calls = calls + n` increment would give.
 *
 * Catching up has a bound, though: the recompute is only as correct as the detail rows still there to be
 * counted, so it must never run for a day whose details were already pruned, or it overwrites the day's
 * true totals with a partial count. `ToolInvocationLogMapper.deleteRolledOut` gates on this table for
 * exactly that reason.
 *
 * That statement is deliberately not tenant-scoped, unlike every read in `ToolInvocationLogMapper`: the
 * rollup is a server-wide maintenance job that writes one row per tenant, and its own tenant predicate
 * would fold only the workspace that happens to run the job.
 *
 * SQL lives in `resources/mapper/ToolInvocationStatsMapper.xml`.
 */
@Mapper
interface ToolInvocationStatsMapper {

    /**
     * Recompute one day in full.
     *
     * @param statDate Day to fold, `yyyy-MM-dd`
     * @return Rows touched; MySQL counts an updated row as 2 and an unchanged one as 0, so the number is
     * not a row count and callers must not read it as one
     */
    fun upsertDay(
        @Param("statDate") statDate: String,
    ): Int
}
