package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolInvocationLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Tool invocation detail writes and the retention sweep the rollup needs.
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
     * Days the detail table holds but the aggregate does not.
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
}
