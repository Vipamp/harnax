package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SysJob
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * SysJob Mapper interface
 */
@Mapper
interface SysJobMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): SysJob?

    fun insert(sysjob: SysJob): Int

    fun updateById(sysjob: SysJob): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================
    fun selectJobList(
        @Param("keyword") keyword: String?,
        @Param("jobStatus") jobStatus: Int?,
        @Param("currentUsername") currentUsername: String,
    ): List<SysJob>

    /**
     * Query by job name and group
     *
     * @param jobName Job name
     * @param jobGroup Job group name
     * @return Scheduled job entity
     */
    fun selectByNameAndGroup(
        @Param("jobName") jobName: String,
        @Param("jobGroup") jobGroup: String,
    ): SysJob?

    /**
     * Query all running scheduled jobs
     *
     * @return List of running scheduled jobs
     */
    fun selectRunningJobs(): List<SysJob>

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int
}
