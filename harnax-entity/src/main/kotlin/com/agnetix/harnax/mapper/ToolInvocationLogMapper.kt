package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolInvocationLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Tool invocation detail writes, the retention sweep the rollup needs, and the two reads the metrics page
 * answers an agent or session dimension and one page of single calls from.
 *
 * Every read here names a tenant, and `tenantId` is a non-null `Long` where a read is tenant-scoped: there
 * is no value that means "all tenants". The rollup's three statements are the exception and are commented
 * at their own methods, because they run over the whole server rather than over one caller's window.
 *
 * SQL lives in `resources/mapper/ToolInvocationLogMapper.xml`.
 */
@Mapper
interface ToolInvocationLogMapper {

    /**
     * Append one batch of invocation rows.
     *
     * @param logs Rows to write; an empty list must not reach this method — the caller returns early
     * @return Number of rows written
     */
    fun batchInsert(
        @Param("list") logs: List<ToolInvocationLog>,
    ): Int

    /**
     * Days whose details have no aggregate row yet, oldest first.
     *
     * This is a membership test and not a work queue: a day folded earlier is not named again even when its
     * details have moved on since, so the fold stays complete for the current and the previous day only
     * because the hourly job of Task 10 rolls both on every run, whatever this list returns.
     *
     * Detail rows with no tenant are excluded on purpose: the aggregate table's `tenant_id` is `NOT NULL`,
     * so no such day would ever gain a row to be compared against, and every hourly run would report it
     * pending. Those rows are still pruned by [deleteRolledOut], which names them as its one exception.
     *
     * @param floor Oldest day to consider, `yyyy-MM-dd`; normally the retention window's edge
     * @return Pending days, oldest first, as `yyyy-MM-dd`
     */
    fun selectUnrolledDates(
        @Param("floor") floor: String,
    ): List<String>

    /**
     * Prune detail rows older than [before], and only those the rollup has already folded.
     *
     * The gate is the aggregate table rather than the date arithmetic because that is the only way I6
     * survives a missed run: a day that never got rolled up stays queryable instead of vanishing. Rows
     * with no tenant are the exception and leave on the window alone — nothing will ever fold them.
     *
     * @param before Cutoff instant, `yyyy-MM-dd HH:mm:ss`
     * @return Number of detail rows deleted
     */
    fun deleteRolledOut(
        @Param("before") before: String,
    ): Int

    /**
     * Per-subject totals for a dimension the aggregate table does not carry: `agent` or `session`, named by
     * [groupBy], which the service narrows to those two literals before it reaches this statement.
     *
     * Bounded by the detail retention: this answers only for the window in which single calls still exist.
     */
    fun selectSubjectTotalsFromDetail(
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("tenantId") tenantId: Long,
        @Param("kind") kind: String?,
        @Param("groupBy") groupBy: String,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * One page of single calls, newest first, within the retention window.
     *
     * The widest mapper method in the repository, and it stays that way on purpose: the query object lives in
     * `harnax-admin` and this module may not depend on it, so the alternatives are ten parameters or a lie
     * about what an example query's equality semantics mean for a range predicate. The service narrows them.
     */
    fun selectInvocationPage(
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("tenantId") tenantId: Long,
        @Param("kind") kind: String?,
        @Param("toolName") toolName: String?,
        @Param("mcpId") mcpId: Long?,
        @Param("cliId") cliId: Long?,
        @Param("agentId") agentId: Long?,
        @Param("sessionId") sessionId: String?,
        @Param("outcome") outcome: String?,
    ): MutableList<MutableMap<String?, Any?>?>?
}
