package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.TokenStats
import com.agnetix.harnax.entity.dto.LatestCallUsage
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Token Consumption Statistics Mapper interface
 * SQL configuration in resources/mapper/TokenStatsMapper.xml
 *
 * Every aggregation read here names a tenant, and `tenantId` is a non-null `Long` on purpose: there is no value
 * that means "all tenants", so a caller that cannot resolve one has nothing to ask for. The predicate itself
 * sits once in the shared `tenantAndTimeWindow` fragment that all 20 aggregations include, which is the
 * only reason adding a 21st aggregation cannot quietly come out unscoped.
 *
 * The one exception is [selectLatestCallUsage]: it answers a question about a single conversation and
 * is served by the same session-scoped, internal-only read path as the chat history, so its predicate is the
 * session id. Nothing else here may follow that shape.
 *
 * Rows inserted with a NULL `tenant_id` (a run nothing attributed it to) match no aggregation here. That is
 * the trade for not guessing a tenant on the way in.
 */
@Mapper
interface TokenStatsMapper {

    // ==================== Basic CRUD Methods ====================

    fun insert(tokenStats: TokenStats): Int

    /**
     * The most recent model call billed for one session, with the row that says so.
     *
     * Ordered by `id` rather than `ts` because several calls of one turn share a `ts` written to the second,
     * and insertion order is the only thing that says which of them was last. That same order is what lets a
     * reader tell a bill written before a context rewrite from one written after it, which is why the row id
     * travels with the number instead of this answering the number alone.
     *
     * @param sessionId Session to read; no tenant predicate — see the interface comment
     * @return that session's newest row, or null when nothing has been recorded for it
     */
    fun selectLatestCallUsage(
        @Param("sessionId") sessionId: String,
    ): LatestCallUsage?

    /**
     * Aggregate query Token consumption by model
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param tenantId  Owning tenant, required — see the interface comment
     * @return Aggregated result list
     */
    fun aggregateByModel(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Aggregate query Token consumption by session
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param tenantId  Owning tenant, required — see the interface comment
     * @return Aggregated result list
     */
    fun aggregateBySession(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Aggregate query Token consumption by agent
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param tenantId  Owning tenant, required — see the interface comment
     * @return Aggregated result list
     */
    fun aggregateByAgent(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Get overall statistics
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param tenantId  Owning tenant, required — see the interface comment
     * @return Overall statistics
     */
    fun getOverallStats(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableMap<String?, Any?>?

    /**
     * Query Token consumption time series data by hour
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param tenantId  Owning tenant, required — see the interface comment
     * @return Time series data list
     */
    fun getTimeSeriesByHour(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by day
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param tenantId  Owning tenant, required — see the interface comment
     * @return Time series data list
     */
    fun getTimeSeriesByDay(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by week
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param tenantId  Owning tenant, required — see the interface comment
     * @return Time series data list
     */
    fun getTimeSeriesByWeek(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by month
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param tenantId  Owning tenant, required — see the interface comment
     * @return Time series data list
     */
    fun getTimeSeriesByMonth(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by model + hour
     */
    fun getModelTimeSeriesByHour(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by model + day
     */
    fun getModelTimeSeriesByDay(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by model + week
     */
    fun getModelTimeSeriesByWeek(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by model + month
     */
    fun getModelTimeSeriesByMonth(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by agent + hour
     */
    fun getAgentTimeSeriesByHour(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by agent + day
     */
    fun getAgentTimeSeriesByDay(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by agent + week
     */
    fun getAgentTimeSeriesByWeek(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by agent + month
     */
    fun getAgentTimeSeriesByMonth(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by session + hour
     */
    fun getSessionTimeSeriesByHour(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by session + day
     */
    fun getSessionTimeSeriesByDay(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by session + week
     */
    fun getSessionTimeSeriesByWeek(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * Query Token consumption time series data by session + month
     */
    fun getSessionTimeSeriesByMonth(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * The landing page's window question: how much ran inside one time window, in how many
     * conversations and on how many agents.
     *
     * Answers one row whatever the window holds, because COUNT over no rows is 0 and the token sum is
     * COALESCEd — a workspace with no consumption reads as zeros rather than as an absent row.
     *
     * Sits here rather than in `DashboardMapper` so the predicate comes from `tenantAndTimeWindow` like
     * every other aggregation over this table, instead of becoming a second copy of the tenant filter.
     *
     * @param startTime Window start, inclusive, `yyyy-MM-dd HH:mm:ss`
     * @param endTime   Window end, inclusive, `yyyy-MM-dd HH:mm:ss`
     * @param tenantId  Owning tenant, required — see the interface comment
     * @return One row with `calls`, `tokens`, `sessions`, `agents`
     */
    fun getDashboardWindowStats(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableMap<String?, Any?>?

    /**
     * Daily consumption over a window, for the landing page's trend.
     *
     * Same bucket as [getTimeSeriesByDay] but with a call count next to the token total — the series the
     * token-monitor draws never needed to know how many requests a day held, the overview does.
     *
     * Days without consumption answer no row at all; padding the series to one point per day is the
     * service's job, not this statement's.
     *
     * @param startTime Window start, inclusive, `yyyy-MM-dd HH:mm:ss`
     * @param endTime   Window end, inclusive, `yyyy-MM-dd HH:mm:ss`
     * @param tenantId  Owning tenant, required — see the interface comment
     * @return One row per day holding consumption, with `timePoint`, `calls`, `tokens`
     */
    fun getDashboardDailyTrend(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
        @Param("tenantId") tenantId: Long,
    ): MutableList<MutableMap<String?, Any?>?>?
}
