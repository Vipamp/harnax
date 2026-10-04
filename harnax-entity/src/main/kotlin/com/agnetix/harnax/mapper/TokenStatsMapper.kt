package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.TokenStats
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
 * The one exception is [selectLatestInputTokenBySession]: it answers a question about a single conversation and
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
     * The billed input tokens of the most recent model call recorded for one session.
     *
     * Ordered by `id` rather than `ts` because several calls of one turn share a `ts` written to the second,
     * and insertion order is the only thing that says which of them was last.
     *
     * @param sessionId Session to read; no tenant predicate — see the interface comment
     * @return `input_token` of that session's newest row, or null when nothing has been recorded for it
     */
    fun selectLatestInputTokenBySession(
        @Param("sessionId") sessionId: String,
    ): Long?

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
}
