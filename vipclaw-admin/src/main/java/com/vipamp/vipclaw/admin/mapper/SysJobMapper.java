package com.vipamp.vipclaw.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vipamp.vipclaw.admin.entity.SysJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 定时任务 Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Mapper
public interface SysJobMapper extends BaseMapper<SysJob> {

    /**
     * 查询所有运行中的任务
     */
    @Select("SELECT * FROM sys_job WHERE job_status = 1 AND active = 1")
    List<SysJob> selectRunningJobs();

    /**
     * 根据任务名称和组名查询任务
     */
    @Select("SELECT * FROM sys_job WHERE job_name = #{jobName} AND job_group = #{jobGroup} AND active = 1")
    SysJob selectByNameAndGroup(@Param("jobName") String jobName, @Param("jobGroup") String jobGroup);
}
