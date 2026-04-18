package com.vipamp.vipclaw.admin.service

import com.baomidou.mybatisplus.extension.service.IService
import com.vipamp.vipclaw.admin.dto.TokenStatsAggregationResponse
import com.vipamp.vipclaw.admin.entity.TokenStats

/**
 * Token 消耗统计服务接口
 *
 * @author vipamp
 * @since 2026-04-11
 */
interface TokenStatsService : IService<TokenStats> {

    /**
     * 保存 Token 消耗记录
     *
     * @param tokenStats Token 统计实体
     * @return 保存结果
     */
    fun saveTokenStats(tokenStats: TokenStats): Boolean

    /**
     * 获取聚合统计数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 聚合统计响应
     */
    fun getAggregationStats(startTime: String, endTime: String): TokenStatsAggregationResponse

    /**
     * 获取时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param granularity 时间粒度 (hour/day/week/month)
     * @return 聚合统计响应(包含时序数据)
     */
    fun getTimeSeriesData(startTime: String, endTime: String, granularity: String): TokenStatsAggregationResponse

    /**
     * 获取按模型的时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param granularity 时间粒度 (hour/day/month)
     * @return 聚合统计响应(包含时序数据)
     */
    fun getModelTimeSeriesData(startTime: String, endTime: String, granularity: String): TokenStatsAggregationResponse

    /**
     * 获取按智能体的时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param granularity 时间粒度 (hour/day/month)
     * @return 聚合统计响应(包含时序数据)
     */
    fun getAgentTimeSeriesData(startTime: String, endTime: String, granularity: String): TokenStatsAggregationResponse

    /**
     * 获取按会话的时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param granularity 时间粒度 (hour/day/month)
     * @return 聚合统计响应(包含时序数据)
     */
    fun getSessionTimeSeriesData(startTime: String, endTime: String, granularity: String): TokenStatsAggregationResponse
}
