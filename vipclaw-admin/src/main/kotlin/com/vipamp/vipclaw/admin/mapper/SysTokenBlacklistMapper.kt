package com.vipamp.vipclaw.admin.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.vipamp.vipclaw.admin.entity.SysTokenBlacklist
import org.apache.ibatis.annotations.Mapper

/**
 * Token 黑名单 Mapper
 */
@Mapper
interface SysTokenBlacklistMapper : BaseMapper<SysTokenBlacklist>
