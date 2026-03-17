package com.vipamp.vipclaw.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vipamp.vipclaw.admin.entity.SysJobLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 定时任务日志 Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Mapper
public interface SysJobLogMapper extends BaseMapper<SysJobLog> {

    /**
     * 根据任务 ID 查询日志列表
     */
    @Select("SELECT * FROM sys_job_log WHERE job_id = #{jobId} ORDER BY create_time DESC")
    List<SysJobLog> selectByJobId(@Param("jobId") Long jobId);
}
