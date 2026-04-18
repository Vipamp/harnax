package com.vipamp.vipclaw.admin.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.vipamp.vipclaw.admin.entity.ProcessLogEntity
import org.apache.ibatis.annotations.Mapper

/**
 * 处理日志 Mapper 接口
 *
 * @author vipamp
 * @since 2026-04-12
 */
@Mapper
interface ProcessLogMapper : BaseMapper<ProcessLogEntity>
