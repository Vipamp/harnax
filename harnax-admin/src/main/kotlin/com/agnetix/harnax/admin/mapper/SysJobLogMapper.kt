package com.agnetix.harnax.admin.mapper

import com.agnetix.harnax.admin.entity.SysJobLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/**
 * SysJobLog Mapper interface
 */
@Mapper
interface SysJobLogMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): SysJobLog?

    fun insert(sysjoblog: SysJobLog): Int

    fun updateById(sysjoblog: SysJobLog): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================
    fun selectJobLogList(
        @Param("jobId") jobId: Long?,
        @Param("jobName") jobName: String?,
        @Param("status") status: Int?,
        @Param("startTime") startTime: LocalDateTime?,
        @Param("endTime") endTime: LocalDateTime?,
    ): List<SysJobLog>

    fun selectByJobId(@Param("jobId") jobId: Long): List<SysJobLog>
}
