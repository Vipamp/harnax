package com.vipamp.vipclaw.admin.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.vipamp.vipclaw.admin.entity.Skill
import org.apache.ibatis.annotations.Mapper

/**
 * 技能 Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Mapper
interface SkillMapper : BaseMapper<Skill>
