package com.vipamp.vipclaw.admin.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.vipamp.vipclaw.admin.entity.TokenStats
import org.apache.ibatis.annotations.Mapper

/**
 * Token 消耗统计 Mapper 接口
 *
 * @author vipamp
 * @since 2026-04-11
 */
@Mapper
interface TokenStatsMapper : BaseMapper<TokenStats>
