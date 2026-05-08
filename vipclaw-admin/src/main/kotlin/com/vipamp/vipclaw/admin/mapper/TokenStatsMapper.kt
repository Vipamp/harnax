package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.TokenStats
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Token 消耗统计 Mapper 接口
 * SQL 配置在 resources/mapper/TokenStatsMapper.xml 中
 *
 * @author vipamp
 * @since 2026-04-11
 */
@Mapper
interface TokenStatsMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun insert(tokenStats: TokenStats): Int

    /**
     * 按模型聚合查询 Token 消耗
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 聚合结果列表
     */
    fun aggregateByModel(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按会话聚合查询 Token 消耗
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 聚合结果列表
     */
    fun aggregateBySession(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按智能体聚合查询 Token 消耗
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 聚合结果列表
     */
    fun aggregateByAgent(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 获取总体统计信息
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 总体统计
     */
    fun getOverallStats(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableMap<String?, Any?>?

    /**
     * 按小时查询 Token 消耗时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 时序数据列表
     */
    fun getTimeSeriesByHour(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按天查询 Token 消耗时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 时序数据列表
     */
    fun getTimeSeriesByDay(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按周查询 Token 消耗时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 时序数据列表
     */
    fun getTimeSeriesByWeek(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按月查询 Token 消耗时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 时序数据列表
     */
    fun getTimeSeriesByMonth(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按模型+小时查询 Token 消耗时序数据
     */
    fun getModelTimeSeriesByHour(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按模型+天查询 Token 消耗时序数据
     */
    fun getModelTimeSeriesByDay(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按模型+月查询 Token 消耗时序数据
     */
    fun getModelTimeSeriesByMonth(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按智能体+小时查询 Token 消耗时序数据
     */
    fun getAgentTimeSeriesByHour(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按智能体+天查询 Token 消耗时序数据
     */
    fun getAgentTimeSeriesByDay(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按智能体+月查询 Token 消耗时序数据
     */
    fun getAgentTimeSeriesByMonth(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按会话+小时查询 Token 消耗时序数据
     */
    fun getSessionTimeSeriesByHour(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按会话+天查询 Token 消耗时序数据
     */
    fun getSessionTimeSeriesByDay(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /**
     * 按会话+月查询 Token 消耗时序数据
     */
    fun getSessionTimeSeriesByMonth(
        @Param("startTime") startTime: String?,
        @Param("endTime") endTime: String?,
    ): MutableList<MutableMap<String?, Any?>?>?
}
