package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysJobLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/**
 * SysJobLog Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface SysJobLogMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun selectById(@Param("id") id: Long): SysJobLog?

    fun insert(sysjoblog: SysJobLog): Int

    fun updateById(sysjoblog: SysJobLog): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    fun selectJobLogList(
        @Param("jobId") jobId: Long?,
        @Param("jobName") jobName: String?,
        @Param("status") status: Int?,
        @Param("startTime") startTime: LocalDateTime?,
        @Param("endTime") endTime: LocalDateTime?
    ): List<SysJobLog>
}
