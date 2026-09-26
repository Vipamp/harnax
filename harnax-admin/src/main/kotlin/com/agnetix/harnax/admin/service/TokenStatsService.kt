package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.TokenStatsAggregationResponse

/**
 * Token consumption statistics service interface
 *
 * Every read names a tenant. The writes are not here on purpose: a consumption row is recorded by the
 * runtime (`TokenStatAdaptorImpl`), and an admin-side save gave the caller a way to invent one.
 */
interface TokenStatsService {

    /**
     * Get aggregation statistics
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param tenantId  Owning tenant the numbers are read for
     * @return Aggregation statistics response
     */
    fun getAggregationStats(startTime: String, endTime: String, tenantId: Long): TokenStatsAggregationResponse

    /**
     * Get time series data
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param granularity Time granularity (hour/day/week/month)
     * @param tenantId  Owning tenant the numbers are read for
     * @return Aggregation statistics response (includes time series data)
     */
    fun getTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        tenantId: Long,
    ): TokenStatsAggregationResponse

    /**
     * Get time series data by model
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param granularity Time granularity (hour/day/month)
     * @param tenantId  Owning tenant the numbers are read for
     * @return Aggregation statistics response (includes time series data)
     */
    fun getModelTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        tenantId: Long,
    ): TokenStatsAggregationResponse

    /**
     * Get time series data by agent
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param granularity Time granularity (hour/day/month)
     * @param tenantId  Owning tenant the numbers are read for
     * @return Aggregation statistics response (includes time series data)
     */
    fun getAgentTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        tenantId: Long,
    ): TokenStatsAggregationResponse

    /**
     * Get time series data by session
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param granularity Time granularity (hour/day/month)
     * @param tenantId  Owning tenant the numbers are read for
     * @return Aggregation statistics response (includes time series data)
     */
    fun getSessionTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        tenantId: Long,
    ): TokenStatsAggregationResponse
}
