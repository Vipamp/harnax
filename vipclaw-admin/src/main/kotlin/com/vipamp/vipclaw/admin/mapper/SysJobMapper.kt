package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysJob
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * SysJob Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface SysJobMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun selectById(@Param("id") id: Long): SysJob?

    fun insert(sysjob: SysJob): Int

    fun updateById(sysjob: SysJob): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    fun selectJobList(
        @Param("keyword") keyword: String?,
        @Param("jobStatus") jobStatus: Int?,
        @Param("currentUsername") currentUsername: String
    ): List<SysJob>

    /**
     * 根据任务名称和组名查询
     *
     * @param jobName 任务名称
     * @param jobGroup 任务组名
     * @return 定时任务实体
     */
    fun selectByNameAndGroup(
        @Param("jobName") jobName: String,
        @Param("jobGroup") jobGroup: String
    ): SysJob?

    /**
     * 查询所有运行中的定时任务
     *
     * @return 运行中的定时任务列表
     */
    fun selectRunningJobs(): List<SysJob>

    fun selectActiveById(@Param("id") id: Long): SysJob?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    fun logicalDelete(@Param("id") id: Long): Int
}
