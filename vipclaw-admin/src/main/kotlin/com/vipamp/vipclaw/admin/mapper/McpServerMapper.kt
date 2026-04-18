package com.vipamp.vipclaw.admin.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.vipamp.vipclaw.admin.entity.McpServer
import org.apache.ibatis.annotations.Mapper

/**
 * MCP 服务 Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-12
 */
@Mapper
interface McpServerMapper : BaseMapper<McpServer>
