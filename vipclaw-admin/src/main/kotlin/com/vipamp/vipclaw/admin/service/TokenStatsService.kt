package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.TokenStatsAggregationResponse
import com.vipamp.vipclaw.admin.entity.TokenStats

/**
 * Token consumption statistics service interface
 */
interface TokenStatsService {

    /**
     * Save token consumption record
     *
     * @param tokenStats Token statistics entity
     * @return Save result
     */
    fun saveTokenStats(tokenStats: TokenStats): Boolean

    /**
     * Get aggregation statistics
     *
     * @param startTime Start time
     * @param endTime   End time
     * @return Aggregation statistics response
     */
    fun getAggregationStats(startTime: String, endTime: String): TokenStatsAggregationResponse

    /**
     * Get time series data
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param granularity Time granularity (hour/day/week/month)
     * @return Aggregation statistics response (includes time series data)
     */
    fun getTimeSeriesData(startTime: String, endTime: String, granularity: String): TokenStatsAggregationResponse

    /**
     * Get time series data by model
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param granularity Time granularity (hour/day/month)
     * @return Aggregation statistics response (includes time series data)
     */
    fun getModelTimeSeriesData(startTime: String, endTime: String, granularity: String): TokenStatsAggregationResponse

    /**
     * Get time series data by agent
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param granularity Time granularity (hour/day/month)
     * @return Aggregation statistics response (includes time series data)
     */
    fun getAgentTimeSeriesData(startTime: String, endTime: String, granularity: String): TokenStatsAggregationResponse

    /**
     * Get time series data by session
     *
     * @param startTime Start time
     * @param endTime   End time
     * @param granularity Time granularity (hour/day/month)
     * @return Aggregation statistics response (includes time series data)
     */
    fun getSessionTimeSeriesData(startTime: String, endTime: String, granularity: String): TokenStatsAggregationResponse
}
