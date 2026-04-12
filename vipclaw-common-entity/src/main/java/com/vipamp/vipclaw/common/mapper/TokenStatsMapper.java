package com.vipamp.vipclaw.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vipamp.vipclaw.common.entity.TokenStats;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Token 消耗统计 Mapper 接口
 *
 * @author vipamp
 * @since 2026-04-11
 */
public interface TokenStatsMapper extends BaseMapper<TokenStats> {

    /**
     * 按模型聚合查询 Token 消耗
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 聚合结果列表
     */
    @Select("SELECT " +
            "ts.chat_model_id as modelId, " +
            "m.name as modelName, " +
            "mp.display_name as providerName, " +
            "SUM(ts.input_token) as totalInputToken, " +
            "SUM(ts.output_token) as totalOutputToken, " +
            "SUM(ts.total_token) as grandTotalToken, " +
            "SUM(ts.fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN model m ON ts.chat_model_id = m.id " +
            "LEFT JOIN model_provider mp ON m.provider_id = mp.id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.chat_model_id, m.name, mp.display_name " +
            "ORDER BY grandTotalToken DESC")
    List<Map<String, Object>> aggregateByModel(@Param("startTime") String startTime,
                                                @Param("endTime") String endTime);

    /**
     * 按会话聚合查询 Token 消耗
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 聚合结果列表
     */
    @Select("SELECT " +
            "ts.session_id as sessionId, " +
            "s.title as sessionTitle, " +
            "SUM(ts.input_token) as totalInputToken, " +
            "SUM(ts.output_token) as totalOutputToken, " +
            "SUM(ts.total_token) as grandTotalToken, " +
            "SUM(ts.fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN session s ON ts.session_id = s.session_id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.session_id, s.title " +
            "ORDER BY grandTotalToken DESC")
    List<Map<String, Object>> aggregateBySession(@Param("startTime") String startTime,
                                                  @Param("endTime") String endTime);

    /**
     * 按智能体聚合查询 Token 消耗
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 聚合结果列表
     */
    @Select("SELECT " +
            "ts.agent_id as agentId, " +
            "a.name as agentName, " +
            "SUM(ts.input_token) as totalInputToken, " +
            "SUM(ts.output_token) as totalOutputToken, " +
            "SUM(ts.total_token) as grandTotalToken, " +
            "SUM(ts.fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN agent a ON ts.agent_id = a.id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.agent_id, a.name " +
            "ORDER BY grandTotalToken DESC")
    List<Map<String, Object>> aggregateByAgent(@Param("startTime") String startTime,
                                                @Param("endTime") String endTime);

    /**
     * 获取总体统计信息
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 总体统计
     */
    @Select("SELECT " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee, " +
            "COUNT(DISTINCT agent_id) as agentCount, " +
            "COUNT(DISTINCT session_id) as sessionCount, " +
            "COUNT(DISTINCT chat_model_id) as modelCount " +
            "FROM token_stats " +
            "WHERE ts BETWEEN #{startTime} AND #{endTime}")
    Map<String, Object> getOverallStats(@Param("startTime") String startTime,
                                         @Param("endTime") String endTime);

    /**
     * 按小时查询 Token 消耗时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 时序数据列表
     */
    @Select("SELECT " +
            "DATE_FORMAT(ts, '%Y-%m-%d %H:00:00') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats " +
            "WHERE ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY DATE_FORMAT(ts, '%Y-%m-%d %H:00:00') " +
            "ORDER BY timePoint ASC")
    List<Map<String, Object>> getTimeSeriesByHour(@Param("startTime") String startTime,
                                                    @Param("endTime") String endTime);

    /**
     * 按天查询 Token 消耗时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 时序数据列表
     */
    @Select("SELECT " +
            "DATE_FORMAT(ts, '%Y-%m-%d 00:00:00') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats " +
            "WHERE ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY DATE_FORMAT(ts, '%Y-%m-%d 00:00:00') " +
            "ORDER BY timePoint ASC")
    List<Map<String, Object>> getTimeSeriesByDay(@Param("startTime") String startTime,
                                                   @Param("endTime") String endTime);

    /**
     * 按周查询 Token 消耗时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 时序数据列表
     */
    @Select("SELECT " +
            "DATE_FORMAT(ts, '%x-%v') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats " +
            "WHERE ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY DATE_FORMAT(ts, '%x-%v') " +
            "ORDER BY timePoint ASC")
    List<Map<String, Object>> getTimeSeriesByWeek(@Param("startTime") String startTime,
                                                    @Param("endTime") String endTime);

    /**
     * 按月查询 Token 消耗时序数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 时序数据列表
     */
    @Select("SELECT " +
            "DATE_FORMAT(ts, '%Y-%m') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats " +
            "WHERE ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY DATE_FORMAT(ts, '%Y-%m') " +
            "ORDER BY timePoint ASC")
    List<Map<String, Object>> getTimeSeriesByMonth(@Param("startTime") String startTime,
                                                     @Param("endTime") String endTime);

    /**
     * 按模型+小时查询 Token 消耗时序数据
     */
    @Select("SELECT " +
            "ts.chat_model_id as modelId, " +
            "m.name as modelName, " +
            "DATE_FORMAT(ts, '%Y-%m-%d %H:00:00') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN model m ON ts.chat_model_id = m.id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.chat_model_id, m.name, DATE_FORMAT(ts, '%Y-%m-%d %H:00:00') " +
            "ORDER BY timePoint ASC, grandTotalToken DESC")
    List<Map<String, Object>> getModelTimeSeriesByHour(@Param("startTime") String startTime,
                                                         @Param("endTime") String endTime);

    /**
     * 按模型+天查询 Token 消耗时序数据
     */
    @Select("SELECT " +
            "ts.chat_model_id as modelId, " +
            "m.name as modelName, " +
            "DATE_FORMAT(ts, '%Y-%m-%d 00:00:00') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN model m ON ts.chat_model_id = m.id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.chat_model_id, m.name, DATE_FORMAT(ts, '%Y-%m-%d 00:00:00') " +
            "ORDER BY timePoint ASC, grandTotalToken DESC")
    List<Map<String, Object>> getModelTimeSeriesByDay(@Param("startTime") String startTime,
                                                        @Param("endTime") String endTime);

    /**
     * 按模型+月查询 Token 消耗时序数据
     */
    @Select("SELECT " +
            "ts.chat_model_id as modelId, " +
            "m.name as modelName, " +
            "DATE_FORMAT(ts, '%Y-%m') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN model m ON ts.chat_model_id = m.id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.chat_model_id, m.name, DATE_FORMAT(ts, '%Y-%m') " +
            "ORDER BY timePoint ASC, grandTotalToken DESC")
    List<Map<String, Object>> getModelTimeSeriesByMonth(@Param("startTime") String startTime,
                                                          @Param("endTime") String endTime);

    /**
     * 按智能体+小时查询 Token 消耗时序数据
     */
    @Select("SELECT " +
            "ts.agent_id as agentId, " +
            "a.name as agentName, " +
            "DATE_FORMAT(ts, '%Y-%m-%d %H:00:00') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN agent a ON ts.agent_id = a.id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.agent_id, a.name, DATE_FORMAT(ts, '%Y-%m-%d %H:00:00') " +
            "ORDER BY timePoint ASC, grandTotalToken DESC")
    List<Map<String, Object>> getAgentTimeSeriesByHour(@Param("startTime") String startTime,
                                                         @Param("endTime") String endTime);

    /**
     * 按智能体+天查询 Token 消耗时序数据
     */
    @Select("SELECT " +
            "ts.agent_id as agentId, " +
            "a.name as agentName, " +
            "DATE_FORMAT(ts, '%Y-%m-%d 00:00:00') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN agent a ON ts.agent_id = a.id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.agent_id, a.name, DATE_FORMAT(ts, '%Y-%m-%d 00:00:00') " +
            "ORDER BY timePoint ASC, grandTotalToken DESC")
    List<Map<String, Object>> getAgentTimeSeriesByDay(@Param("startTime") String startTime,
                                                        @Param("endTime") String endTime);

    /**
     * 按智能体+月查询 Token 消耗时序数据
     */
    @Select("SELECT " +
            "ts.agent_id as agentId, " +
            "a.name as agentName, " +
            "DATE_FORMAT(ts, '%Y-%m') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN agent a ON ts.agent_id = a.id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.agent_id, a.name, DATE_FORMAT(ts, '%Y-%m') " +
            "ORDER BY timePoint ASC, grandTotalToken DESC")
    List<Map<String, Object>> getAgentTimeSeriesByMonth(@Param("startTime") String startTime,
                                                          @Param("endTime") String endTime);

    /**
     * 按会话+小时查询 Token 消耗时序数据
     */
    @Select("SELECT " +
            "ts.session_id as sessionId, " +
            "s.title as sessionTitle, " +
            "DATE_FORMAT(ts, '%Y-%m-%d %H:00:00') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN session s ON ts.session_id = s.session_id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.session_id, s.title, DATE_FORMAT(ts, '%Y-%m-%d %H:00:00') " +
            "ORDER BY timePoint ASC, grandTotalToken DESC")
    List<Map<String, Object>> getSessionTimeSeriesByHour(@Param("startTime") String startTime,
                                                           @Param("endTime") String endTime);

    /**
     * 按会话+天查询 Token 消耗时序数据
     */
    @Select("SELECT " +
            "ts.session_id as sessionId, " +
            "s.title as sessionTitle, " +
            "DATE_FORMAT(ts, '%Y-%m-%d 00:00:00') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN session s ON ts.session_id = s.session_id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.session_id, s.title, DATE_FORMAT(ts, '%Y-%m-%d 00:00:00') " +
            "ORDER BY timePoint ASC, grandTotalToken DESC")
    List<Map<String, Object>> getSessionTimeSeriesByDay(@Param("startTime") String startTime,
                                                          @Param("endTime") String endTime);

    /**
     * 按会话+月查询 Token 消耗时序数据
     */
    @Select("SELECT " +
            "ts.session_id as sessionId, " +
            "s.title as sessionTitle, " +
            "DATE_FORMAT(ts, '%Y-%m') as timePoint, " +
            "SUM(input_token) as totalInputToken, " +
            "SUM(output_token) as totalOutputToken, " +
            "SUM(total_token) as grandTotalToken, " +
            "SUM(fee) as totalFee " +
            "FROM token_stats ts " +
            "LEFT JOIN session s ON ts.session_id = s.session_id " +
            "WHERE ts.ts BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY ts.session_id, s.title, DATE_FORMAT(ts, '%Y-%m') " +
            "ORDER BY timePoint ASC, grandTotalToken DESC")
    List<Map<String, Object>> getSessionTimeSeriesByMonth(@Param("startTime") String startTime,
                                                            @Param("endTime") String endTime);
}
