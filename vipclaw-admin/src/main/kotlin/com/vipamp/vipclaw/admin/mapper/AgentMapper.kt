package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Agent
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 智能体 Mapper 接口
 * SQL 配置在 resources/mapper/AgentMapper.xml 中
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface AgentMapper {

    /**
     * 根据 ID 查询
     */
    fun selectById(@Param("id") id: Long): Agent?

    /**
     * 插入
     */
    fun insert(agent: Agent): Int

    /**
     * 更新
     */
    fun updateById(agent: Agent): Int

    /**
     * 逻辑删除
     */
    fun deleteById(@Param("id") id: Long): Int

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    /**
     * 查询列表（带条件）
     */
    fun selectAgentList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String
    ): List<Agent>
}
