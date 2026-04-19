package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysJob
import org.apache.ibatis.annotations.*

/**
 * SysJob Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface SysJobMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM sys_job WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): SysJob?

    @Insert(
        """
        INSERT INTO sys_job (
            job_name, job_group, job_class, cron_expression, job_status, concurrent, description, is_public, creator, active, create_time, update_time
        ) VALUES (
            #{jobName}, #{jobGroup}, #{jobClass}, #{cronExpression}, #{jobStatus}, #{concurrent}, #{description}, #{isPublic}, #{creator}, #{active}, #{createTime}, #{updateTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(sysjob: SysJob): Int

    @Update(
        """
        UPDATE sys_job SET
            job_name = #{jobName},
            job_group = #{jobGroup},
            job_class = #{jobClass},
            cron_expression = #{cronExpression},
            job_status = #{jobStatus},
            concurrent = #{concurrent},
            description = #{description},
            is_public = #{isPublic},
            creator = #{creator},
            active = #{active},
            update_time = #{updateTime}
        WHERE id = #{id}
        """
    )
    fun updateById(sysjob: SysJob): Int

    @Update("UPDATE sys_job SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    @Select(
        """
        <script>
            SELECT * FROM sys_job 
            WHERE active = 1
            AND (is_public = 1 OR creator = #{currentUsername})
            <if test='keyword != null and keyword != ""'>
                AND job_name LIKE CONCAT('%', #{keyword}, '%')
            </if>
            <if test='jobStatus != null'>
                AND job_status = #{jobStatus}
            </if>
            ORDER BY update_time DESC
        </script>
    """
    )
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
    @Select("SELECT * FROM sys_job WHERE job_name = #{jobName} AND job_group = #{jobGroup} AND active = 1 LIMIT 1")
    fun selectByNameAndGroup(
        @Param("jobName") jobName: String,
        @Param("jobGroup") jobGroup: String
    ): SysJob?

    /**
     * 查询所有运行中的定时任务
     *
     * @return 运行中的定时任务列表
     */
    @Select("SELECT * FROM sys_job WHERE job_status = 1 AND active = 1")
    fun selectRunningJobs(): List<SysJob>

    @Select("SELECT * FROM sys_job WHERE id = #{id} AND active = 1 LIMIT 1")
    fun selectActiveById(@Param("id") id: Long): SysJob?

    @Update("UPDATE sys_job SET job_status = #{status}, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    @Update("UPDATE sys_job SET active = 0, update_time = NOW() WHERE id = #{id} AND active = 1")
    fun logicalDelete(@Param("id") id: Long): Int
}
