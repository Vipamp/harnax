package com.vipamp.vipclaw.admin.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.vipamp.vipclaw.admin.entity.SysUser
import org.apache.ibatis.annotations.Mapper

/**
 * 用户 Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-06
 */
@Mapper
interface SysUserMapper : BaseMapper<SysUser>
