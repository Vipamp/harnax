package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysJobLog
import org.apache.ibatis.annotations.*
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

    @Select("SELECT * FROM sys_job_log WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): SysJobLog?

    @Insert(
        """
        INSERT INTO sys_job_log (
            job_id, job_name, job_group, invoke_target, job_message, status, start_time, end_time, creator, create_time
        ) VALUES (
            #{jobId}, #{jobName}, #{jobGroup}, #{invokeTarget}, #{jobMessage}, #{status}, #{startTime}, #{endTime}, #{creator}, #{createTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(sysjoblog: SysJobLog): Int

    @Update(
        """
        UPDATE sys_job_log SET
            job_id = #{jobId},
            job_name = #{jobName},
            job_group = #{jobGroup},
            invoke_target = #{invokeTarget},
            job_message = #{jobMessage},
            status = #{status},
            start_time = #{startTime},
            end_time = #{endTime},
            creator = #{creator}
        WHERE id = #{id}
        """
    )
    fun updateById(sysjoblog: SysJobLog): Int

    @Update("UPDATE sys_job_log SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    @Select(
        """
        <script>
            SELECT * FROM sys_job_log 
            WHERE 1=1
            <if test='jobId != null'>
                AND job_id = #{jobId}
            </if>
            <if test='jobName != null and jobName != ""'>
                AND job_name LIKE CONCAT('%', #{jobName}, '%')
            </if>
            <if test='status != null'>
                AND status = #{status}
            </if>
            <if test='startTime != null'>
                AND create_time >= #{startTime}
            </if>
            <if test='endTime != null'>
                AND create_time &lt;= #{endTime}
            </if>
            ORDER BY create_time DESC
        </script>
    """
    )
    fun selectJobLogList(
        @Param("jobId") jobId: Long?,
        @Param("jobName") jobName: String?,
        @Param("status") status: Int?,
        @Param("startTime") startTime: LocalDateTime?,
        @Param("endTime") endTime: LocalDateTime?
    ): List<SysJobLog>
}
