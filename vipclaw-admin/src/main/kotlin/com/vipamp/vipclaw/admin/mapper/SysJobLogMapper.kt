package com.vipamp.vipclaw.admin.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.vipamp.vipclaw.admin.entity.SysJobLog
import org.apache.ibatis.annotations.Mapper

/**
 * 定时任务日志 Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Mapper
interface SysJobLogMapper : BaseMapper<SysJobLog>
